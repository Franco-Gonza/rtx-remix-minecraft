/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.nvidia.rtxremix.forge.terrain.capture.SectionMeshData;
import com.nvidia.rtxremix.forge.texture.AtlasAnimationForwarder;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AtlasAnimationForwarderTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
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
    void testSectionMeshCategories() {
        assertEquals(0, SectionMeshData.CAT_SOLID);
        assertEquals(1, SectionMeshData.CAT_CUTOUT);
        assertEquals(2, SectionMeshData.CAT_TRANSLUCENT);
        assertEquals(3, SectionMeshData.CAT_OVERLAY);
        assertEquals(4, SectionMeshData.CAT_PARTICLE);
        assertEquals(5, SectionMeshData.CAT_COUNT);
    }
}
