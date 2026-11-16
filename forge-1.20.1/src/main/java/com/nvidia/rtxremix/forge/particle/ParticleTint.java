/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.particle;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SmokeParticle;

/**
 * Adjusts particle tints when vanilla lightmap colours are unsuitable for path tracing.
 */
public final class ParticleTint {

    public static final float SOOT_GREY = 0.04f;

    private ParticleTint() {}

    public static boolean isSoot(Particle particle) {
        return particle instanceof SmokeParticle;
    }
}
