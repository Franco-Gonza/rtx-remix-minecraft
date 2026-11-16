/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.nvidia.rtxremix.forge.texture.AtlasAnimationForwarder;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
public abstract class SpriteAtlasTextureMixin {

    @Shadow @Final private ResourceLocation location;

    @Inject(method = "cycleAnimationFrames", at = @At("HEAD"))
    private void rtxremix$onAnimatedSpriteTickStart(CallbackInfo ci) {
        AtlasAnimationForwarder.beginAtlasUpload(location);
    }

    @Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
    private void rtxremix$onAnimatedSpriteTickEnd(CallbackInfo ci) {
        AtlasAnimationForwarder.endAtlasUpload();
    }
}
