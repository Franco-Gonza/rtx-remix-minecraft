/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.forge.client.ForgeRemixRenderer;
import net.minecraft.client.renderer.texture.HttpTexture;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HttpTexture.class)
public abstract class HttpTextureMixin {

    @Inject(method = "upload(Lcom/mojang/blaze3d/platform/NativeImage;)V", at = @At("RETURN"))
    private void rtxremix$onSkinTextureUploaded(NativeImage image, CallbackInfo ci) {
        ForgeRemixRenderer renderer = ForgeRemixRenderer.getInstance();
        if (renderer != null) {
            ResourceLocation loc = ((SimpleTextureAccessor) this).rtxremix$getLocation();
            if (loc != null) {
                renderer.invalidateSkinTexture(loc);
            }
        }
    }
}
