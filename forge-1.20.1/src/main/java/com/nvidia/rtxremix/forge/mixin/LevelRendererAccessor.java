/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invoker accessor to invoke vanilla's snow and rain pass after raytraced overlay blit.
 */
@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {

    @Invoker("renderSnowAndRain")
    void rtxremix$renderSnowAndRain(LightTexture lightTexture, float partialTick, double camX, double camY, double camZ);

    @Invoker("renderClouds")
    void rtxremix$renderClouds(com.mojang.blaze3d.vertex.PoseStack poseStack, org.joml.Matrix4f projectionMatrix, float partialTick, double camX, double camY, double camZ);
}
