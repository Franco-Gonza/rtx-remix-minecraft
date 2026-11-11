/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin.compatibility;

import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.forge.entity.EntityRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pseudo-mixin for Citadel (Alex's Mobs, Alex's Caves) AdvancedModelBox.
 * Hooks model rendering to register box transforms into EntityRenderContext.
 */
@Pseudo
@Mixin(targets = "com.github.alexthe666.citadel.client.model.AdvancedModelBox", remap = false)
public abstract class CitadelAdvancedModelBoxMixin {

    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private void rtxremix$trackCitadelBox(
            PoseStack poseStack,
            Object vertexConsumer,
            int packedLight,
            int packedOverlay,
            float red, float green, float blue, float alpha,
            CallbackInfo ci) {
        EntityRenderContext ctx = EntityRenderContext.get();
        if (ctx.isActive() && poseStack != null) {
            ctx.pushBone(poseStack.last().pose());
        }
    }
}
