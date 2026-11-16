/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.forge.texture.AtlasAnimationForwarder;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.class)
public abstract class SpriteContentsUploadMixin {

    @Shadow @Final private ResourceLocation name;
    @Shadow public abstract int width();
    @Shadow public abstract int height();

    @Inject(method = "upload(IIII[Lcom/mojang/blaze3d/platform/NativeImage;)V", at = @At("HEAD"))
    private void rtxremix$onAnimationUpload(int texX, int texY, int srcX, int srcY,
                                            NativeImage[] mips, CallbackInfo ci) {
        if (!AtlasAnimationForwarder.isCapturing()) return;
        if (mips == null || mips.length == 0 || mips[0] == null) return;

        NativeImage mip0 = mips[0];
        long basePtr = ((NativeImageAccessor) (Object) mip0).getPointer();
        if (basePtr == 0) return;
        int imgWidth = mip0.getWidth();
        int imgHeight = mip0.getHeight();
        int frameWidth = this.width();
        int frameHeight = this.height();
        int rowPitch = imgWidth * 4;

        AtlasAnimationForwarder.addPatch(name, texX, texY, srcX, srcY, frameWidth, frameHeight,
                imgWidth, imgHeight, basePtr, rowPitch);
    }
}
