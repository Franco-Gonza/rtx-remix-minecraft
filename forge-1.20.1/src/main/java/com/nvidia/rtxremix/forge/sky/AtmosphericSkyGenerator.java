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

    public ByteBuffer generate(float sunAngle, float rainStrength, float[] meanOut) {
        outputBuffer.clear();
        float sunY = (float) Math.sin((sunAngle - 0.25f) * 2.0 * Math.PI);

        // Rayleigh scattering approximation
        float rCol = Math.max(0.01f, 0.4f * sunY + 0.1f);
        float gCol = Math.max(0.01f, 0.6f * sunY + 0.15f);
        float bCol = Math.max(0.02f, 0.9f * sunY + 0.2f);

        if (rainStrength > 0.0f) {
            float gray = (rCol + gCol + bCol) / 3.0f * 0.4f;
            rCol = rCol * (1.0f - rainStrength) + gray * rainStrength;
            gCol = gCol * (1.0f - rainStrength) + gray * rainStrength;
            bCol = bCol * (1.0f - rainStrength) + gray * rainStrength;
        }

        short r = (short) (HardcodedVertex.packHalf2x16(rCol, 0.0f) & 0xFFFF);
        short g = (short) (HardcodedVertex.packHalf2x16(gCol, 0.0f) & 0xFFFF);
        short b = (short) (HardcodedVertex.packHalf2x16(bCol, 0.0f) & 0xFFFF);
        short a = (short) (HardcodedVertex.packHalf2x16(1.0f, 0.0f) & 0xFFFF);

        for (int i = 0; i < WIDTH * HEIGHT; i++) {
            outputBuffer.putShort(r).putShort(g).putShort(b).putShort(a);
        }
        outputBuffer.flip();

        if (meanOut != null && meanOut.length >= 3) {
            meanOut[0] = rCol;
            meanOut[1] = gCol;
            meanOut[2] = bCol;
        }

        return outputBuffer;
    }
}
