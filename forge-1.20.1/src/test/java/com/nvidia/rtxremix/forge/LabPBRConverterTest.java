/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.forge.texture.LabPBRConverter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LabPBRConverterTest {

    @Test
    void testSmoothnessToRoughness() {
        assertEquals(255, LabPBRConverter.smoothnessToRoughness(0), "Smoothness 0 must yield maximum roughness 255");
        assertEquals(0, LabPBRConverter.smoothnessToRoughness(255), "Smoothness 255 must yield minimum roughness 0");

        int midRoughness = LabPBRConverter.smoothnessToRoughness(128);
        assertTrue(midRoughness > 50 && midRoughness < 75, "Perceptual mid-smoothness should map to quadratic roughness curve");
    }

    @Test
    void testF0ToMetallic() {
        assertEquals(0, LabPBRConverter.f0ToMetallic(0), "F0=0 must be dielectric (0)");
        assertEquals(0, LabPBRConverter.f0ToMetallic(229), "F0=229 below 230 threshold must be dielectric (0)");
        assertEquals(255, LabPBRConverter.f0ToMetallic(230), "F0=230 at threshold must be metallic (255)");
        assertEquals(255, LabPBRConverter.f0ToMetallic(255), "F0=255 must be metallic (255)");
    }

    @Test
    void testSssIntensity() {
        assertEquals(0, LabPBRConverter.sssIntensity(0), "Porosity < 65 must have 0 SSS intensity");
        assertEquals(0, LabPBRConverter.sssIntensity(64), "Porosity 64 must have 0 SSS intensity");
        assertEquals(0, LabPBRConverter.sssIntensity(65), "Threshold 65 maps to 0");
        assertEquals(255, LabPBRConverter.sssIntensity(255), "Maximum 255 maps to 255");
    }

    @Test
    void testNormalZReconstruction() {
        NativeImage img = new NativeImage(1, 1, false);
        try {
            // RGBA in Little Endian ABGR: R=128 (0x80), G=128 (0x80), B=255 (0xFF), A=255 (0xFF)
            // abgr = 0xFF_FF_80_80
            img.setPixelRGBA(0, 0, 0xFFFF8080);
            byte[] normalAtlas = new byte[4];
            LabPBRConverter.blitNormal(normalAtlas, 1, img, 0, 0, 1, 1);

            assertEquals((byte) 128, normalAtlas[0], "Normal X must be 128");
            assertEquals((byte) 128, normalAtlas[1], "Normal Y must be 128");
            int z = normalAtlas[2] & 0xFF;
            assertTrue(z >= 250, "Z component for flat normal must be reconstructed near 255");
            assertEquals((byte) 255, normalAtlas[3], "Normal Alpha must be 255");
        } finally {
            img.close();
        }
    }

    @Test
    void testApplyAO() {
        NativeImage img = new NativeImage(1, 1, false);
        try {
            // Blue channel is AO (shift 16). 128 = ~50% occlusion
            // abgr = 0xFF_80_00_00 -> R=0, G=0, B=128, A=255
            img.setPixelRGBA(0, 0, 0xFF800000);
            byte[] albedo = new byte[] { (byte) 200, (byte) 100, (byte) 50, (byte) 255 };
            LabPBRConverter.applyAO(albedo, 1, img, 0, 0, 1, 1);

            int r = albedo[0] & 0xFF;
            int g = albedo[1] & 0xFF;
            int b = albedo[2] & 0xFF;
            assertEquals(100, r, 2, "Albedo R should be halved by 50% AO");
            assertEquals(50, g, 2, "Albedo G should be halved by 50% AO");
            assertEquals(25, b, 2, "Albedo B should be halved by 50% AO");
        } finally {
            img.close();
        }
    }
}
