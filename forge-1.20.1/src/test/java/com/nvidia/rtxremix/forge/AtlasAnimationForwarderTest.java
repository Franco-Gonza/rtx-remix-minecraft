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

    @Test
    void testSidecarAtlasRegistrationAndClear() {
        AtlasAnimationForwarder.clearSidecarAtlases();
        AtlasAnimationForwarder.registerSidecarAtlas(0x2001L);
        AtlasAnimationForwarder.registerSidecarAtlas(0x2002L);
        // Duplicate should be ignored
        AtlasAnimationForwarder.registerSidecarAtlas(0x2001L);

        AtlasAnimationForwarder.beginAtlasUpload(TextureAtlas.LOCATION_BLOCKS);
        AtlasAnimationForwarder.addPatch(new ResourceLocation("minecraft", "block/lava_still"),
                0, 0, 0, 16, 16, 16, 16, 256, 0x1000L, 64);
        AtlasAnimationForwarder.endAtlasUpload();

        AtlasAnimationForwarder.clear();
        AtlasAnimationForwarder.clearSidecarAtlases();
    }

    @Test
    void testPbrChannelDefinitionsAndRegistration() {
        assertEquals(com.nvidia.rtxremix.TextureFormat.RGBA8_UNORM, AtlasAnimationForwarder.PbrChannel.NORMAL.format);
        assertEquals(4, AtlasAnimationForwarder.PbrChannel.NORMAL.bytesPerPixel);

        assertEquals(com.nvidia.rtxremix.TextureFormat.R8_UNORM, AtlasAnimationForwarder.PbrChannel.HEIGHT.format);
        assertEquals(1, AtlasAnimationForwarder.PbrChannel.HEIGHT.bytesPerPixel);

        assertEquals(com.nvidia.rtxremix.TextureFormat.R8_UNORM, AtlasAnimationForwarder.PbrChannel.ROUGHNESS.format);
        assertEquals(1, AtlasAnimationForwarder.PbrChannel.ROUGHNESS.bytesPerPixel);

        assertEquals(com.nvidia.rtxremix.TextureFormat.R8_UNORM, AtlasAnimationForwarder.PbrChannel.METALLIC.format);
        assertEquals(1, AtlasAnimationForwarder.PbrChannel.METALLIC.bytesPerPixel);

        assertEquals(com.nvidia.rtxremix.TextureFormat.RGBA8_UNORM, AtlasAnimationForwarder.PbrChannel.EMISSIVE.format);
        assertEquals(4, AtlasAnimationForwarder.PbrChannel.EMISSIVE.bytesPerPixel);

        assertEquals(com.nvidia.rtxremix.TextureFormat.RGBA8_UNORM, AtlasAnimationForwarder.PbrChannel.SSS.format);
        assertEquals(4, AtlasAnimationForwarder.PbrChannel.SSS.bytesPerPixel);

        AtlasAnimationForwarder.clearSidecarAtlases();
        AtlasAnimationForwarder.registerPbrAtlas(0x3001L, AtlasAnimationForwarder.PbrChannel.NORMAL);
        AtlasAnimationForwarder.registerPbrAtlas(0x3002L, AtlasAnimationForwarder.PbrChannel.ROUGHNESS);
        AtlasAnimationForwarder.registerPbrAtlas(0x3003L, AtlasAnimationForwarder.PbrChannel.METALLIC);

        assertEquals(0x3001L, AtlasAnimationForwarder.getPbrAtlas(AtlasAnimationForwarder.PbrChannel.NORMAL));
        assertEquals(0x3002L, AtlasAnimationForwarder.getPbrAtlas(AtlasAnimationForwarder.PbrChannel.ROUGHNESS));
        assertEquals(0x3003L, AtlasAnimationForwarder.getPbrAtlas(AtlasAnimationForwarder.PbrChannel.METALLIC));
        assertNull(AtlasAnimationForwarder.getPbrAtlas(AtlasAnimationForwarder.PbrChannel.HEIGHT));

        AtlasAnimationForwarder.clearSidecarAtlases();
        assertNull(AtlasAnimationForwarder.getPbrAtlas(AtlasAnimationForwarder.PbrChannel.NORMAL));
    }

    @Test
    void testMultiFormatBatchEntryR8VsRGBA8RowPitch() {
        long fakeBase = 0x200000L;

        // 1. Valid R8 patch: 16x16, rowPitch = 16 (1 byte per pixel for R8)
        AtlasAnimationForwarder.PendingPatch r8Patch = new AtlasAnimationForwarder.PendingPatch(
                10, 20, 2, 4, 16, 16, 32, 64, fakeBase, 16);
        TextureBatchEntry r8Entry = AtlasAnimationForwarder.buildBatchEntry(
                1L, List.of(r8Patch), com.nvidia.rtxremix.TextureFormat.R8_UNORM);
        assertNotNull(r8Entry);
        assertEquals(1, r8Entry.patches.length);
        assertEquals(com.nvidia.rtxremix.TextureFormat.R8_UNORM, r8Entry.patches[0].format);
        assertEquals(16, r8Entry.patches[0].rowPitch);
        // Required bytes for R8: (16 - 1) * 16 + 16 * 1 = 240 + 16 = 256
        assertEquals(256, r8Entry.patches[0].pixelData.capacity());
        // Offset for R8: fakeBase + 4 * 16 + 2 * 1 = fakeBase + 64 + 2 = fakeBase + 66
        assertEquals(fakeBase + 66L, MemoryUtil.memAddress(r8Entry.patches[0].pixelData));

        // 2. Invalid R8 patch: rowPitch = 15 (< width * 1) -> rejected
        AtlasAnimationForwarder.PendingPatch badR8Pitch = new AtlasAnimationForwarder.PendingPatch(
                10, 20, 2, 4, 16, 16, 32, 64, fakeBase, 15);
        assertNull(AtlasAnimationForwarder.buildBatchEntry(
                1L, List.of(badR8Pitch), com.nvidia.rtxremix.TextureFormat.R8_UNORM));

        // 3. Valid RGBA8 patch: 16x16, rowPitch = 64 (4 bytes per pixel)
        AtlasAnimationForwarder.PendingPatch rgbaPatch = new AtlasAnimationForwarder.PendingPatch(
                10, 20, 2, 4, 16, 16, 32, 64, fakeBase, 64);
        TextureBatchEntry rgbaEntry = AtlasAnimationForwarder.buildBatchEntry(
                1L, List.of(rgbaPatch), com.nvidia.rtxremix.TextureFormat.RGBA8_UNORM);
        assertNotNull(rgbaEntry);
        assertEquals(com.nvidia.rtxremix.TextureFormat.RGBA8_UNORM, rgbaEntry.patches[0].format);
        assertEquals(64, rgbaEntry.patches[0].rowPitch);
        // Required bytes for RGBA8: (16 - 1) * 64 + 16 * 4 = 960 + 64 = 1024
        assertEquals(1024, rgbaEntry.patches[0].pixelData.capacity());
        // Offset for RGBA8: fakeBase + 4 * 64 + 2 * 4 = fakeBase + 256 + 8 = fakeBase + 264
        assertEquals(fakeBase + 264L, MemoryUtil.memAddress(rgbaEntry.patches[0].pixelData));

        // 4. Invalid RGBA8 patch: rowPitch = 63 (< width * 4) -> rejected
        AtlasAnimationForwarder.PendingPatch badRgbaPitch = new AtlasAnimationForwarder.PendingPatch(
                10, 20, 2, 4, 16, 16, 32, 64, fakeBase, 63);
        assertNull(AtlasAnimationForwarder.buildBatchEntry(
                1L, List.of(badRgbaPitch), com.nvidia.rtxremix.TextureFormat.RGBA8_UNORM));
    }

    @Test
    void testDirectBufferLifecycleSafety() {
        // DirectBufferPool acquire and release verification
        java.nio.ByteBuffer buf = com.nvidia.rtxremix.forge.util.DirectBufferPool.acquire(1024);
        assertNotNull(buf);
        assertTrue(buf.isDirect());
        assertEquals(0, buf.position());

        // Safe release back to pool
        com.nvidia.rtxremix.forge.util.DirectBufferPool.release(buf);

        // Multiple acquire/release cycle
        java.util.List<java.nio.ByteBuffer> buffers = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            buffers.add(com.nvidia.rtxremix.forge.util.DirectBufferPool.acquire(256));
        }
        for (java.nio.ByteBuffer b : buffers) {
            com.nvidia.rtxremix.forge.util.DirectBufferPool.release(b);
        }
    }
}
