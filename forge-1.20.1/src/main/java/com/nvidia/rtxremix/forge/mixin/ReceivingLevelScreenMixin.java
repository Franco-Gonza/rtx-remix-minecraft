/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.nvidia.rtxremix.forge.client.ForgeRemixRenderer;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ensures BlockDataQueue is drained while ReceivingLevelScreen is showing,
 * preventing section compiler worker threads from stalling on chunk build queue capacity.
 */
@Mixin(ReceivingLevelScreen.class)
public abstract class ReceivingLevelScreenMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void rtxremix$drainQueueOnLoadingScreen(CallbackInfo ci) {
        ForgeRemixRenderer renderer = ForgeRemixRenderer.getInstance();
        if (renderer.isInitialized()) {
            renderer.submitScene();
        }
    }
}
