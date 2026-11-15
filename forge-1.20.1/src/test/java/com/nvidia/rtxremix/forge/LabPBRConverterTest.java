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
        // 1. Metallic binary threshold at 230
        assertEquals(0, LabPBRConverter.f0ToMetallic(0), "F0=0 must be dielectric (0)");
        assertEquals(0, LabPBRConverter.f0ToMetallic(100), "F0=100 must be dielectric (0)");
        assertEquals(0, LabPBRConverter.f0ToMetallic(229), "F0=229 strictly below 230 threshold must be dielectric (0)");
        assertEquals(255, LabPBRConverter.f0ToMetallic(230), "F0=230 at threshold must be metallic (255)");
        assertEquals(255, LabPBRConverter.f0ToMetallic(255), "F0=255 must be metallic (255)");

        // 2. LabPBR 1.3 linear dielectric F0 reflectance curve: F0 = g / 255.0f
        assertEquals(0.0f, LabPBRConverter.f0ToDielectricF0(0), 1e-5f, "g=0 must have F0=0.0");
        assertEquals(10.0f / 255.0f, LabPBRConverter.f0ToDielectricF0(10), 1e-5f, "g=10 must map to 10/255 F0");
        assertEquals(128.0f / 255.0f, LabPBRConverter.f0ToDielectricF0(128), 1e-5f, "g=128 must map linearly to 128/255 F0");
        assertEquals(229.0f / 255.0f, LabPBRConverter.f0ToDielectricF0(229), 1e-5f, "g=229 must map linearly to 229/255 F0");
        assertEquals(1.0f, LabPBRConverter.f0ToDielectricF0(230), "Conductors >= 230 must return 1.0 F0");
        assertEquals(1.0f, LabPBRConverter.f0ToDielectricF0(255), "Generic conductor 255 must return 1.0 F0");

        // 3. Dielectric IOR calculation: IOR = (1 + sqrt(F0)) / (1 - sqrt(F0))
        assertEquals(1.0f, LabPBRConverter.f0ToDielectricIOR(0), 1e-4f, "g=0 (F0=0) must have air IOR = 1.0");
        float ior10 = LabPBRConverter.f0ToDielectricIOR(10);
        float expectedIor10 = (float) ((1.0 + Math.sqrt(10.0 / 255.0)) / (1.0 - Math.sqrt(10.0 / 255.0)));
        assertEquals(expectedIor10, ior10, 1e-3f, "g=10 must match Fresnel IOR formula");
        float ior128 = LabPBRConverter.f0ToDielectricIOR(128);
        float expectedIor128 = (float) ((1.0 + Math.sqrt(128.0 / 255.0)) / (1.0 - Math.sqrt(128.0 / 255.0)));
        assertEquals(expectedIor128, ior128, 1e-3f, "g=128 must match Fresnel IOR formula");
        float ior229 = LabPBRConverter.f0ToDielectricIOR(229);
        float expectedIor229 = (float) ((1.0 + Math.sqrt(229.0 / 255.0)) / (1.0 - Math.sqrt(229.0 / 255.0)));
        assertEquals(expectedIor229, ior229, 0.05f, "g=229 must match Fresnel IOR formula ~37.2");
        assertTrue(Float.isInfinite(LabPBRConverter.f0ToDielectricIOR(230)), "Conductors >= 230 must yield infinite IOR");
        assertTrue(Float.isInfinite(LabPBRConverter.f0ToDielectricIOR(255)), "Conductor 255 must yield infinite IOR");

        // 4. LabPBR 1.3 metal profile classification (230..255)
        assertFalse(LabPBRConverter.isMetallic(0));
        assertFalse(LabPBRConverter.isMetallic(229));
        assertTrue(LabPBRConverter.isMetallic(230));
        assertTrue(LabPBRConverter.isMetallic(255));

        assertEquals("Dielectric", LabPBRConverter.getMetalName(0));
        assertEquals("Dielectric", LabPBRConverter.getMetalName(100));
        assertEquals("Dielectric", LabPBRConverter.getMetalName(229));

        assertEquals("Iron", LabPBRConverter.getMetalName(230));
        assertEquals("Gold", LabPBRConverter.getMetalName(231));
        assertEquals("Aluminium", LabPBRConverter.getMetalName(232));
        assertEquals("Chrome", LabPBRConverter.getMetalName(233));
        assertEquals("Copper", LabPBRConverter.getMetalName(234));
        assertEquals("Lead", LabPBRConverter.getMetalName(235));
        assertEquals("Platinum", LabPBRConverter.getMetalName(236));
        assertEquals("Silver", LabPBRConverter.getMetalName(237));

        for (int m = 238; m <= 254; m++) {
            assertEquals("Reserved Conductor", LabPBRConverter.getMetalName(m), "Metal ID " + m + " should be Reserved Conductor");
        }
        assertEquals("Generic Conductor", LabPBRConverter.getMetalName(255));
    }

    @Test
    void testSssIntensityAndDataDetection() {
        assertEquals(0, LabPBRConverter.sssIntensity(0), "Porosity < 65 must have 0 SSS intensity");
        assertEquals(0, LabPBRConverter.sssIntensity(64), "Porosity 64 must have 0 SSS intensity");
        assertEquals(0, LabPBRConverter.sssIntensity(65), "Threshold 65 maps to 0");
        int midSss = LabPBRConverter.sssIntensity(160);
        assertEquals((160 - 65) * 255 / 190, midSss, "B=160 should map linearly in (b-65)*255/190");
        assertEquals(255, LabPBRConverter.sssIntensity(255), "Maximum 255 maps to 255");

        NativeImage sImg = new NativeImage(1, 1, false);
        try {
            // Blue channel is SSS (shift 16). 160 >= 65 -> has SSS data
            // abgr = 0xFF_A0_00_00 (B=160, A=255)
            sImg.setPixelRGBA(0, 0, 0xFFA00000);
            assertTrue(LabPBRConverter.hasSSSData(sImg), "sImg with B=160 must detect SSS data");

            byte[] sssAtlas = new byte[1];
            LabPBRConverter.blitSSS(sssAtlas, 1, sImg, 0, 0, 1, 1);
            int expectedIntensity = (160 - 65) * 255 / 190;
            assertEquals((byte) expectedIntensity, sssAtlas[0], "SSS R8 channel should match intensity");
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
