/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import net.minecraft.client.particle.SingleQuadParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(SingleQuadParticle.class)
public interface SingleQuadParticleAccessor {
    @Accessor("quadSize")
    float rtxremix$getQuadSize();

    @Invoker("getU0")
    float rtxremix$getU0();

    @Invoker("getU1")
    float rtxremix$getU1();

    @Invoker("getV0")
    float rtxremix$getV0();

    @Invoker("getV1")
    float rtxremix$getV1();
}
