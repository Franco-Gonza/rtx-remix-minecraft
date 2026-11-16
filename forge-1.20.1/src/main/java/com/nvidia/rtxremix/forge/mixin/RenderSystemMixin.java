/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.nvidia.rtxremix.forge.client.ForgeRemixRenderer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Redirects the buffer swap inside RenderSystem.flipFrame to Remix's
 * present, so Vulkan owns the swapchain whenever the renderer is initialized.
 * Before that, and if initialization fails, the GLFW swap runs unchanged.
 */
@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {

    @Redirect(
        method = "flipFrame",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwSwapBuffers(J)V"),
        remap = false
    )
    private static void rtxremix$redirectSwapBuffers(long window) {
        ForgeRemixRenderer renderer = ForgeRemixRenderer.getInstance();
        if (renderer.isInitialized() && renderer.hasInterop()) {
            renderer.presentFrameVk();
        } else {
            GLFW.glfwSwapBuffers(window);
        }
    }
}
