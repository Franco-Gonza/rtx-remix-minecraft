/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.forge.client.ForgeRemixRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures block entities at vanilla's dispatch point and redirects them to RTX Remix scene submission.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityRenderDispatcherMixin {

    @Inject(method = "render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V",
            at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity> void rtxremix$captureBlockEntityInPlace(
            E be, float partialTicks, PoseStack poseStack,
            MultiBufferSource bufferSource, CallbackInfo ci) {
        ForgeRemixRenderer renderer = ForgeRemixRenderer.getInstance();
        if (renderer != null && renderer.isInitialized() && renderer.isActive() && be != null && be.hasLevel()) {
            boolean captured = renderer.renderBlockEntityInPlace(be, partialTicks);
            if (captured) {
                ci.cancel();
            }
        }
    }
}
