/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin.compatibility;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.forge.entity.EntityRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pseudo-mixin for GeckoLib 4 (Forge 1.20.1) GeoEntityRenderer and GeoReplacedEntityRenderer.
 * Ensures hierarchical bone transforms are registered into EntityRenderContext
 * so RTX Remix creates retained rigid instances instead of CPU vertex recomputation.
 */
@Pseudo
@Mixin(
        targets = {
                "software.bernie.geckolib.renderer.GeoEntityRenderer",
                "software.bernie.geckolib.renderer.GeoReplacedEntityRenderer"
        },
        remap = false
)
public abstract class GeckoLibGeoRendererMixin {

    @Inject(
            method = "renderCubesOfBone(Lcom/mojang/blaze3d/vertex/PoseStack;Lsoftware/bernie/geckolib/cache/object/GeoBone;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private void rtxremix$trackGeckoLibBone(
            PoseStack poseStack,
            @Coerce Object bone,
            VertexConsumer buffer,
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
