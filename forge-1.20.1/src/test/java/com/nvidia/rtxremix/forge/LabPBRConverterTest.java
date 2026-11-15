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
    void testF0ToMetallicAndDielectricReflectance() {
        // Metallic binary threshold
        assertEquals(0, LabPBRConverter.f0ToMetallic(0), "F0=0 must be dielectric (0)");
        assertEquals(0, LabPBRConverter.f0ToMetallic(229), "F0=229 below 230 threshold must be dielectric (0)");
        assertEquals(255, LabPBRConverter.f0ToMetallic(230), "F0=230 at threshold must be metallic (255)");
        assertEquals(255, LabPBRConverter.f0ToMetallic(255), "F0=255 must be metallic (255)");

        // Dielectric F0 reflectance curve
        assertEquals(0.0f, LabPBRConverter.f0ToDielectricF0(0), 1e-4f, "g=0 must have F0=0.0");
        float f0Common = LabPBRConverter.f0ToDielectricF0(115);
        assertEquals(0.0401f, f0Common, 0.002f, "g=115 must map to standard ~0.04 dielectric reflectance");
        float f0Gem = LabPBRConverter.f0ToDielectricF0(229);
        assertEquals(0.080f, f0Gem, 0.001f, "g=229 must map to max dielectric 0.08 F0");
        assertEquals(1.0f, LabPBRConverter.f0ToDielectricF0(230), "Conductors >= 230 must return 1.0 F0");

        // Dielectric IOR computation
        float iorWater = LabPBRConverter.f0ToDielectricIOR(57);
        assertEquals(1.33f, iorWater, 0.05f, "g=57 (F0~0.02) should calculate water IOR ~1.33");
        float iorStandard = LabPBRConverter.f0ToDielectricIOR(115);
        assertEquals(1.50f, iorStandard, 0.03f, "g=115 (F0~0.04) should calculate standard mineral IOR ~1.50");

        // LabPBR 1.3 metal profile classification
        assertFalse(LabPBRConverter.isMetallic(229));
        assertTrue(LabPBRConverter.isMetallic(230));
        assertEquals("Iron", LabPBRConverter.getMetalName(230));
        assertEquals("Gold", LabPBRConverter.getMetalName(231));
        assertEquals("Copper", LabPBRConverter.getMetalName(234));
        assertEquals("Dielectric", LabPBRConverter.getMetalName(115));
    }

    @Test
    void testSssIntensityAndDataDetection() {
        assertEquals(0, LabPBRConverter.sssIntensity(0), "Porosity < 65 must have 0 SSS intensity");
        assertEquals(0, LabPBRConverter.sssIntensity(64), "Porosity 64 must have 0 SSS intensity");
        assertEquals(0, LabPBRConverter.sssIntensity(65), "Threshold 65 maps to 0");
        assertEquals(255, LabPBRConverter.sssIntensity(255), "Maximum 255 maps to 255");

        NativeImage sImg = new NativeImage(1, 1, false);
        try {
            // Blue channel is SSS (shift 16). 160 >= 65 -> has SSS data
            // abgr = 0xFF_A0_00_00 (B=160, A=255)
            sImg.setPixelRGBA(0, 0, 0xFFA00000);
            assertTrue(LabPBRConverter.hasSSSData(sImg), "sImg with B=160 must detect SSS data");

            byte[] sssAtlas = new byte[4];
            LabPBRConverter.blitSSS(sssAtlas, 1, sImg, 0, 0, 1, 1);
            int expectedIntensity = (160 - 65) * 255 / 190;
            assertEquals((byte) expectedIntensity, sssAtlas[0], "SSS R channel should match intensity");
            assertEquals((byte) expectedIntensity, sssAtlas[1], "SSS G channel should match intensity");
            assertEquals((byte) expectedIntensity, sssAtlas[2], "SSS B channel should match intensity");
            assertEquals((byte) 255, sssAtlas[3], "SSS Alpha must be 255");
        } finally {
            sImg.close();
        }
    }

    @Test
    void testSingleScatteringAlbedoBlit() {
        NativeImage sssImg = new NativeImage(1, 1, false);
        try {
            // R=50 (0x32), G=200 (0xC8), B=150 (0x96), A=255
            // abgr = 0xFF_96_C8_32
            sssImg.setPixelRGBA(0, 0, 0xFF96C832);
            byte[] sssAlbedoAtlas = new byte[4];
            LabPBRConverter.blitSingleScatteringAlbedo(sssAlbedoAtlas, 1, sssImg, 0, 0, 1, 1);

            assertEquals((byte) 50, sssAlbedoAtlas[0], "SSS albedo R must match");
            assertEquals((byte) 200, sssAlbedoAtlas[1], "SSS albedo G must match");
            assertEquals((byte) 150, sssAlbedoAtlas[2], "SSS albedo B must match");
            assertEquals((byte) 255, sssAlbedoAtlas[3], "SSS albedo Alpha must be 255");
        } finally {
            sssImg.close();
        }
    }

    @Test
    void testNormalZReconstructionAndPerturbation() {
        NativeImage img = new NativeImage(1, 1, false);
        try {
            // Flat normal: R=128 (0x80), G=128 (0x80), B=255 (0xFF), A=255 (0xFF)
            // abgr = 0xFF_FF_80_80
            img.setPixelRGBA(0, 0, 0xFFFF8080);
            byte[] normalAtlas = new byte[4];
            LabPBRConverter.blitNormal(normalAtlas, 1, img, 0, 0, 1, 1);

            assertEquals((byte) 128, normalAtlas[0], "Normal X must be 128");
            assertEquals((byte) 128, normalAtlas[1], "Normal Y must be 128");
            int z = normalAtlas[2] & 0xFF;
            assertTrue(z >= 250, "Z component for flat normal must be reconstructed near 255");
            assertEquals((byte) 255, normalAtlas[3], "Normal Alpha must be 255");

            // Angled normal: R=192 (nx = +0.505), G=128 (ny = 0.0) -> nz ~ sqrt(1 - 0.505^2) ~ 0.863 -> 220
            // abgr = 0xFF_FF_80_C0
            img.setPixelRGBA(0, 0, 0xFFFF80C0);
            LabPBRConverter.blitNormal(normalAtlas, 1, img, 0, 0, 1, 1);
            assertEquals((byte) 192, normalAtlas[0]);
            int zAngled = normalAtlas[2] & 0xFF;
            assertTrue(zAngled >= 215 && zAngled <= 225, "Z for angled normal must reconstruct to ~220");
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

    @Test
    void testHeightAndRoughnessBlit() {
        NativeImage nImg = new NativeImage(1, 1, false);
        NativeImage sImg = new NativeImage(1, 1, false);
        try {
            // Alpha of _n is height: 180 (0xB4). abgr = 0xB4_00_00_00
            nImg.setPixelRGBA(0, 0, 0xB4000000);
            byte[] heightAtlas = new byte[1];
            LabPBRConverter.blitHeight(heightAtlas, 1, nImg, 0, 0, 1, 1);
            assertEquals((byte) 180, heightAtlas[0], "Height should be extracted directly from _n alpha");

            // Red of _s is smoothness: 200 (0xC8). abgr = 0xFF_00_00_C8
            sImg.setPixelRGBA(0, 0, 0xFF0000C8);
            byte[] roughnessAtlas = new byte[1];
            LabPBRConverter.blitRoughness(roughnessAtlas, 1, sImg, 0, 0, 1, 1);
            int expectedRoughness = LabPBRConverter.smoothnessToRoughness(200);
            assertEquals((byte) expectedRoughness, roughnessAtlas[0], "Roughness should match smoothnessToRoughness(200)");
        } finally {
            nImg.close();
            sImg.close();
        }
    }
}
