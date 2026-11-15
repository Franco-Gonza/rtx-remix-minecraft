/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin.compatibility;

import com.nvidia.rtxremix.forge.entity.EntityRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pseudo-mixin for FaunaEngine's FaunaRenderer (Book of Dragons, Liber Animalium).
 * When RTX Remix entity capture is active (EntityRenderContext.isActive()), forces
 * the renderer to bypass the compiled GPU model path (which draws via direct OpenGL shaders)
 * and route through SkeletonRenderer to emit software cubes and quads directly into
 * the capturing MultiBufferSource.
 */
@Pseudo
@Mixin(targets = "net.magister.liberanimalium.render.FaunaRenderer", remap = false)
public abstract class FaunaRendererMixin {

    @Shadow(remap = false) private boolean gpuAsked;
    @Shadow(remap = false) private boolean compiledActive;
    @Shadow(remap = false) private boolean gpuActive;
    @Shadow(remap = false) private boolean gpuDone;
    @Shadow(remap = false) private int depth;

    @Inject(
            method = "renderRecursively",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private void rtxremix$forceSoftwareCubesForRemix(
            @Coerce Object ctx, @Coerce Object bone, boolean z, CallbackInfo ci) {
        EntityRenderContext rtxCtx = EntityRenderContext.get();
        if (rtxCtx.isActive() && this.depth == 0) {
            this.gpuAsked = true;
            this.compiledActive = false;
            this.gpuActive = false;
            this.gpuDone = true;
        }
    }
}
