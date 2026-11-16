/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.sky;

import com.nvidia.rtxremix.HardcodedVertex;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Computes physically-based Rayleigh and Mie atmospheric scattering textures for RTX Remix sky dome.
 */
public final class AtmosphericSkyGenerator {

    public static final int WIDTH = 256;
    public static final int HEIGHT = 128;
    public static final int OUTPUT_BYTES = WIDTH * HEIGHT * 4 * Short.BYTES;

    private final ByteBuffer outputBuffer = ByteBuffer.allocateDirect(OUTPUT_BYTES).order(ByteOrder.nativeOrder());

    public ByteBuffer generate(float skyAngle, float rainStrength, float[] meanOut) {
        outputBuffer.clear();

        // Minecraft timeOfDay: 0.0 at noon, 0.25 at sunset, 0.5 at midnight, 0.75 at sunrise
        // Adding PI/2 aligns 0.0 with solar zenith (+Y)
        float angle = (float) (skyAngle * 2.0 * Math.PI + Math.PI / 2.0);
        float rawX = (float) Math.cos(angle);
        float rawY = (float) Math.sin(angle);
        float rawZ = 0.0f;

        // Apply 35 degree orbital plane tilt around X axis
        float cr = (float) Math.cos(Math.toRadians(35.0f));
        float sr = (float) Math.sin(Math.toRadians(35.0f));
        float sX = rawX;
        float sY = rawY * cr - rawZ * sr;
        float sZ = rawY * sr + rawZ * cr;

        float sLen = (float) Math.sqrt(sX * sX + sY * sY + sZ * sZ);
        if (sLen > 0.001f) {
            sX /= sLen; sY /= sLen; sZ /= sLen;
        }

        float sunHeight = Math.max(-0.2f, Math.min(1.0f, sY));
        float dayFactor = Math.max(0.0f, Math.min(1.0f, (sunHeight + 0.12f) / 0.28f));

        // Atmospheric colors
        // Zenith day: bright azure blue
        float zenithR = 0.18f * dayFactor + 0.002f;
        float zenithG = 0.38f * dayFactor + 0.004f;
        float zenithB = 0.85f * dayFactor + 0.015f;

        // Horizon day: golden / orange sunrise or warm light blue noon
        float horizonSunsetR = 0.95f;
        float horizonSunsetG = 0.45f;
        float horizonSunsetB = 0.15f;
        float horizonNoonR = 0.65f;
        float horizonNoonG = 0.78f;
        float horizonNoonB = 0.95f;

        float sunsetFactor = (float) Math.exp(-sunHeight * sunHeight * 12.0f);
        float horizonR = (horizonSunsetR * sunsetFactor + horizonNoonR * (1.0f - sunsetFactor)) * dayFactor + 0.003f;
        float horizonG = (horizonSunsetG * sunsetFactor + horizonNoonG * (1.0f - sunsetFactor)) * dayFactor + 0.005f;
        float horizonB = (horizonSunsetB * sunsetFactor + horizonNoonB * (1.0f - sunsetFactor)) * dayFactor + 0.018f;

        // Weather overcast desaturation
        if (rainStrength > 0.0f) {
            float grayZ = (zenithR + zenithG + zenithB) / 3.0f * 0.35f;
            zenithR = zenithR * (1.0f - rainStrength) + grayZ * rainStrength;
            zenithG = zenithG * (1.0f - rainStrength) + grayZ * rainStrength;
            zenithB = zenithB * (1.0f - rainStrength) + grayZ * rainStrength;

            float grayH = (horizonR + horizonG + horizonB) / 3.0f * 0.40f;
            horizonR = horizonR * (1.0f - rainStrength) + grayH * rainStrength;
            horizonG = horizonG * (1.0f - rainStrength) + grayH * rainStrength;
            horizonB = horizonB * (1.0f - rainStrength) + grayH * rainStrength;
        }

        // Fill equirectangular dome
        float sumR = 0, sumG = 0, sumB = 0;
        int upperPixelCount = 0;

        for (int y = 0; y < HEIGHT; y++) {
            // Elevation: y=0 is zenith (+PI/2), y=HEIGHT/2 is horizon (0), y=HEIGHT is nadir (-PI/2)
            float elevation = (float) (Math.PI * 0.5 - (y + 0.5f) / (float) HEIGHT * Math.PI);
            float cosEl = (float) Math.cos(elevation);
            float sinEl = (float) Math.sin(elevation);

            // Altitude gradient: t=1 at zenith, t=0 at horizon
            float t = Math.max(0.0f, Math.min(1.0f, sinEl));

            for (int x = 0; x < WIDTH; x++) {
                // Azimuth: phi in [0, 2PI]
                float phi = (float) ((x + 0.5f) / (float) WIDTH * 2.0 * Math.PI);
                float rx = cosEl * (float) Math.sin(phi);
                float ry = sinEl;
                float rz = cosEl * (float) Math.cos(phi);

                // Angle between ray and sun
                float cosGamma = rx * sX + ry * sY + rz * sZ;

                // Rayleigh phase
                float rayleigh = 0.75f * (1.0f + cosGamma * cosGamma);

                // Mie sun halo (forward scattering peak)
                float g = 0.76f;
                float g2 = g * g;
                float mie = (1.0f - g2) / (float) Math.pow(1.0f + g2 - 2.0f * g * Math.max(-1.0f, Math.min(1.0f, cosGamma)), 1.5f);
                float mieFactor = Math.max(0.0f, cosGamma) > 0.0f ? mie * 0.05f * dayFactor : 0.0f;

                // Base sky color gradient between horizon and zenith
                float baseR = horizonR * (1.0f - t) + zenithR * t;
                float baseG = horizonG * (1.0f - t) + zenithG * t;
                float baseB = horizonB * (1.0f - t) + zenithB * t;

                // If below horizon, darken
                if (ry < 0.0f) {
                    float groundFade = Math.max(0.05f, 1.0f + ry * 2.0f);
                    baseR *= groundFade;
                    baseG *= groundFade;
                    baseB *= groundFade;
                }

                float finalR = baseR * rayleigh + mieFactor * 1.5f;
                float finalG = baseG * rayleigh + mieFactor * 1.3f;
                float finalB = baseB * rayleigh + mieFactor * 1.0f;

                short rHalf = (short) (HardcodedVertex.packHalf2x16(finalR, 0.0f) & 0xFFFF);
                short gHalf = (short) (HardcodedVertex.packHalf2x16(finalG, 0.0f) & 0xFFFF);
                short bHalf = (short) (HardcodedVertex.packHalf2x16(finalB, 0.0f) & 0xFFFF);
                short aHalf = (short) (HardcodedVertex.packHalf2x16(1.0f, 0.0f) & 0xFFFF);

                outputBuffer.putShort(rHalf).putShort(gHalf).putShort(bHalf).putShort(aHalf);

                if (y < HEIGHT / 2) {
                    sumR += finalR;
                    sumG += finalG;
                    sumB += finalB;
                    upperPixelCount++;
                }
            }
        }
        outputBuffer.flip();

        if (meanOut != null && meanOut.length >= 3 && upperPixelCount > 0) {
            meanOut[0] = sumR / upperPixelCount;
            meanOut[1] = sumG / upperPixelCount;
            meanOut[2] = sumB / upperPixelCount;
        }

        return outputBuffer;
    }
}
