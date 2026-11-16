/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("x")
    double rtxremix$getX();

    @Accessor("y")
    double rtxremix$getY();

    @Accessor("z")
    double rtxremix$getZ();

    @Accessor("xo")
    double rtxremix$getXo();

    @Accessor("yo")
    double rtxremix$getYo();

    @Accessor("zo")
    double rtxremix$getZo();

    @Accessor("rCol")
    float rtxremix$getRCol();

    @Accessor("gCol")
    float rtxremix$getGCol();

    @Accessor("bCol")
    float rtxremix$getBCol();

    @Accessor("alpha")
    float rtxremix$getAlpha();

    @Accessor("roll")
    float rtxremix$getRoll();

    @Accessor("oRoll")
    float rtxremix$getORoll();

    @Accessor("removed")
    boolean rtxremix$getRemoved();
}
