/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.nvidia.rtxremix.forge.client.ForgeRemixRenderer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks Minecraft client shutdown lifecycle for graceful cleanup of RTX Remix resources
 * on the render thread before GLFW and OpenGL context termination.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "destroy", at = @At("HEAD"))
    private void rtxremix$onDestroyHead(CallbackInfo ci) {
        ForgeRemixRenderer.getInstance().destroy();
    }

    @Inject(method = "destroy", at = @At("TAIL"))
    private void rtxremix$onDestroyTail(CallbackInfo ci) {
        ForgeRemixRenderer.getInstance().shutdownNativeRuntime();
    }
}
