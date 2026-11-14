/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.forge.terrain.capture.SectionMeshData;
import com.nvidia.rtxremix.forge.texture.AtlasAnimationForwarder;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class AtlasAnimationForwarderTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        AtlasAnimationForwarder.setActive(true);
        AtlasAnimationForwarder.clear();
    }

    @Test
    void testAtlasCaptureState() {
        assertFalse(AtlasAnimationForwarder.isCapturing());

        // Blocks atlas
        AtlasAnimationForwarder.beginAtlasUpload(TextureAtlas.LOCATION_BLOCKS);
        assertTrue(AtlasAnimationForwarder.isCapturing());
        AtlasAnimationForwarder.endAtlasUpload();
        assertFalse(AtlasAnimationForwarder.isCapturing());

        // Particles atlas
        AtlasAnimationForwarder.beginAtlasUpload(TextureAtlas.LOCATION_PARTICLES);
        assertTrue(AtlasAnimationForwarder.isCapturing());
        AtlasAnimationForwarder.endAtlasUpload();
        assertFalse(AtlasAnimationForwarder.isCapturing());

        // Unrelated texture
        AtlasAnimationForwarder.beginAtlasUpload(new ResourceLocation("minecraft", "textures/gui/icons.png"));
        assertFalse(AtlasAnimationForwarder.isCapturing());
    }

    @Test
    void testActiveGate() {
        AtlasAnimationForwarder.beginAtlasUpload(TextureAtlas.LOCATION_BLOCKS);
        assertTrue(AtlasAnimationForwarder.isCapturing());

        AtlasAnimationForwarder.pause();
        assertFalse(AtlasAnimationForwarder.isCapturing());

        AtlasAnimationForwarder.resume();
        // Since pause clears/resets thread-local state, capturing stays false until beginAtlasUpload
        assertFalse(AtlasAnimationForwarder.isCapturing());

        AtlasAnimationForwarder.beginAtlasUpload(TextureAtlas.LOCATION_BLOCKS);
        assertTrue(AtlasAnimationForwarder.isCapturing());
        AtlasAnimationForwarder.endAtlasUpload();
    }

    @Test
    void testThreadIsolation() throws InterruptedException {
        AtlasAnimationForwarder.beginAtlasUpload(TextureAtlas.LOCATION_BLOCKS);
        assertTrue(AtlasAnimationForwarder.isCapturing());

        AtomicBoolean otherThreadCapturing = new AtomicBoolean(true);
        Thread worker = new Thread(() -> {
            otherThreadCapturing.set(AtlasAnimationForwarder.isCapturing());
        });
        worker.start();
        worker.join();

        assertFalse(otherThreadCapturing.get(), "Worker thread must not see capturing state from render thread");
        AtlasAnimationForwarder.endAtlasUpload();
    }

    @Test
    void testBuildBatchEntryOffsetAndPitchMath() {
        long fakeBase = 0x100000L;
        // 16x16 frame inside 32x64 image strip
        // srcX = 4, srcY = 10, rowPitch = 32 * 4 = 128
        AtlasAnimationForwarder.PendingPatch patch = new AtlasAnimationForwarder.PendingPatch(
                32, 64, // dstX, dstY
                4, 10,  // srcX, srcY
                16, 16, // width, height
                32, 64, // srcImageWidth, srcImageHeight
                fakeBase, 128 // pixelPtr, rowPitch
        );

        TextureBatchEntry entry = AtlasAnimationForwarder.buildBatchEntry(1L, List.of(patch));
        assertNotNull(entry);
        assertEquals(1, entry.patches.length);
        assertEquals(32, entry.patches[0].dstX);
        assertEquals(64, entry.patches[0].dstY);
        assertEquals(16, entry.patches[0].width);
        assertEquals(16, entry.patches[0].height);
        assertEquals(128, entry.patches[0].rowPitch);

        // Expected offset: fakeBase + (srcY * rowPitch) + (srcX * 4) = 0x100000 + 10 * 128 + 4 * 4 = 0x100000 + 1280 + 16 = 0x100510
        long actualPtr = MemoryUtil.memAddress(entry.patches[0].pixelData);
        assertEquals(fakeBase + 10L * 128L + 4L * 4L, actualPtr);

        // Required bytes: (height - 1) * rowPitch + width * 4 = 15 * 128 + 16 * 4 = 1920 + 64 = 1984
        assertEquals(1984, entry.patches[0].pixelData.capacity());
    }

    @Test
    void testBuildBatchEntryRejections() {
        long fakeBase = 0x100000L;

        // Negative destination
        AtlasAnimationForwarder.PendingPatch negDst = new AtlasAnimationForwarder.PendingPatch(
                -1, 0, 0, 0, 16, 16, 16, 16, fakeBase, 64);
        assertNull(AtlasAnimationForwarder.buildBatchEntry(1L, List.of(negDst)));

        // rowPitch less than width * 4
        AtlasAnimationForwarder.PendingPatch badPitch = new AtlasAnimationForwarder.PendingPatch(
                0, 0, 0, 0, 16, 16, 16, 16, fakeBase, 32);
        assertNull(AtlasAnimationForwarder.buildBatchEntry(1L, List.of(badPitch)));

        // Source bounds exceeded
        AtlasAnimationForwarder.PendingPatch outOfBounds = new AtlasAnimationForwarder.PendingPatch(
                0, 0, 0, 250, 16, 16, 16, 256, fakeBase, 64);
        assertNull(AtlasAnimationForwarder.buildBatchEntry(1L, List.of(outOfBounds)));

        // Zero handle
        AtlasAnimationForwarder.PendingPatch valid = new AtlasAnimationForwarder.PendingPatch(
                0, 0, 0, 0, 16, 16, 16, 16, fakeBase, 64);
        assertNull(AtlasAnimationForwarder.buildBatchEntry(0L, List.of(valid)));
    }

    @Test
    void testSectionMeshCategories() {
        assertEquals(0, SectionMeshData.CAT_SOLID);
        assertEquals(1, SectionMeshData.CAT_CUTOUT);
        assertEquals(2, SectionMeshData.CAT_TRANSLUCENT);
        assertEquals(3, SectionMeshData.CAT_OVERLAY);
        assertEquals(4, SectionMeshData.CAT_PARTICLE);
        assertEquals(5, SectionMeshData.CAT_COUNT);
    }

    @Test
    void testAtlasPatchBoundsVerification() {
        AtlasAnimationForwarder.beginAtlasUpload(TextureAtlas.LOCATION_BLOCKS);
        // Valid patch: 16x16 within 16x256 image
        AtlasAnimationForwarder.addPatch(0, 0, 0, 16, 16, 16, 16, 256, 0x1000L, 64);
        // Invalid patch: exceeds source height
        AtlasAnimationForwarder.addPatch(0, 0, 0, 250, 16, 16, 16, 256, 0x1000L, 64);
        AtlasAnimationForwarder.endAtlasUpload();
        // Flushing without live API clears gracefully without error
        AtlasAnimationForwarder.flush(null, 1L);
    }
}
