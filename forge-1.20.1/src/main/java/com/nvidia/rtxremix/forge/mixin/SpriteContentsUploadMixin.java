/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.forge.texture.AtlasAnimationForwarder;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.class)
public abstract class SpriteContentsUploadMixin {

    @Inject(method = "upload(IIII[Lcom/mojang/blaze3d/platform/NativeImage;)V", at = @At("HEAD"))
    private void rtxremix$onAnimationUpload(int texX, int texY, int srcX, int srcY,
                                            NativeImage[] mips, CallbackInfo ci) {
        if (!AtlasAnimationForwarder.isCapturing()) return;
        if (mips == null || mips.length == 0 || mips[0] == null) return;

        NativeImage mip0 = mips[0];
        long basePtr = ((NativeImageAccessor) (Object) mip0).getPointer();
        int imgWidth = mip0.getWidth();
        int imgHeight = mip0.getHeight();
        int width = imgWidth;
        int height = imgHeight;
        int rowPitch = imgWidth * 4;

        AtlasAnimationForwarder.addPatch(texX, texY, srcX, srcY, width, height,
                imgWidth, imgHeight, basePtr, rowPitch);
    }
}
