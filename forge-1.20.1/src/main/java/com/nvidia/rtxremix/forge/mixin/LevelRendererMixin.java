/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.forge.client.ForgeRemixRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts vanilla chunk layer rasterization in Forge 1.20.1 to skip redundant OpenGL draws
 * when RTX Remix path tracing owns the terrain.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Inject(method = "renderChunkLayer", at = @At("HEAD"), cancellable = true)
    private void rtxremix$skipVanillaTerrainLayer(
            RenderType renderType, PoseStack poseStack,
            double camX, double camY, double camZ,
            Matrix4f projectionMatrix, CallbackInfo ci) {
        if (ForgeRemixRenderer.isRtxEnabled() && ForgeRemixRenderer.getInstance().hasTerrainMeshes()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true)
    private void rtxremix$captureEntityInPlace(
            net.minecraft.world.entity.Entity entity,
            double camX, double camY, double camZ,
            float tickDelta, PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource bufferSource,
            CallbackInfo ci) {
        if (ForgeRemixRenderer.isRtxEnabled()) {
            ForgeRemixRenderer renderer = ForgeRemixRenderer.getInstance();
            if (renderer != null && renderer.renderEntityInPlace(entity, tickDelta)) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void rtxremix$suppressPreOverlayWeather(
            net.minecraft.client.renderer.LightTexture lightTexture, float partialTick,
            double camX, double camY, double camZ, CallbackInfo ci) {
        if (ForgeRemixRenderer.isRtxEnabled() && !ForgeRemixRenderer.isRenderingPostWeather()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void rtxremix$suppressPreOverlayClouds(
            PoseStack poseStack, Matrix4f projectionMatrix, float partialTick,
            double camX, double camY, double camZ, CallbackInfo ci) {
        if (ForgeRemixRenderer.isRtxEnabled() && !ForgeRemixRenderer.isRenderingPostClouds()) {
            ci.cancel();
        }
    }
}
