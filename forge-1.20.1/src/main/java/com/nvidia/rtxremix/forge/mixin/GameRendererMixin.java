/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.forge.client.ForgeRemixRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks GameRenderer to drive the frame capture and scene submission lifecycle in Remix.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Shadow @Final private Camera mainCamera;

    @Inject(method = "render", at = @At("HEAD"))
    private void rtxremix$onFrameRenderHead(float partialTicks, long nanoTime, boolean renderWorld, CallbackInfo ci) {
        ForgeRemixRenderer renderer = ForgeRemixRenderer.getInstance();
        if (!renderer.isInitialized()) {
            renderer.initializeIfSupported();
        }
    }

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void rtxremix$onRenderLevelHead(float partialTicks, long finishTimeNano, PoseStack poseStack, CallbackInfo ci) {
        ForgeRemixRenderer renderer = ForgeRemixRenderer.getInstance();
        if (!renderer.isInitialized()) {
            renderer.initializeIfSupported();
        }
        if (renderer.isInitialized() && renderer.isActive()) {
            renderer.beginFrame(mainCamera, partialTicks);
        }
    }

    @Inject(method = "renderItemInHand", at = @At("HEAD"))
    private void rtxremix$onRenderItemInHandHead(PoseStack poseStack, Camera camera, float partialTicks, CallbackInfo ci) {
        ForgeRemixRenderer renderer = ForgeRemixRenderer.getInstance();
        if (renderer.isInitialized() && renderer.isActive()) {
            renderer.submitScene();
        }
    }

    @Inject(method = "renderLevel", at = @At("TAIL"))
    private void rtxremix$onRenderLevelTail(float partialTicks, long finishTimeNano, PoseStack poseStack, CallbackInfo ci) {
        ForgeRemixRenderer renderer = ForgeRemixRenderer.getInstance();
        if (renderer.isInitialized() && renderer.isActive() && !renderer.isSceneSubmitted()) {
            renderer.submitScene();
        }
    }
}
