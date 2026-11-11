/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.gl;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the Remix output texture as a full-screen, fully opaque quad on
 * Minecraft's main framebuffer, after the world and first-person model have
 * been submitted and before the screen overlays and HUD.
 */
public class TextureOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("rtxremix/TextureOverlay");
    private final Minecraft client;

    public TextureOverlay() {
        this.client = Minecraft.getInstance();
    }

    public void render(int textureId) {
        if (textureId <= 0 || client == null || client.getWindow() == null) {
            return;
        }

        try {
            renderTextureOverlay(textureId);
        } catch (Exception e) {
            LOGGER.error("Error rendering texture overlay", e);
        }
    }

    private void renderTextureOverlay(int texId) {
        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();

        if (client.getMainRenderTarget() != null) {
            client.getMainRenderTarget().bindWrite(true);
        }

        Matrix4f savedProjection = RenderSystem.getProjectionMatrix();

        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();

        Matrix4f projMatrix = new Matrix4f().setOrtho(
            0.0f, (float) width, (float) height, 0.0f, -1000.0f, 1000.0f
        );
        RenderSystem.setProjectionMatrix(projMatrix, VertexSorting.ORTHOGRAPHIC_Z);

        RenderSystem.getModelViewStack().pushPose();
        RenderSystem.getModelViewStack().setIdentity();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, texId);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        Matrix4f identity = new Matrix4f();
        BufferBuilder bufferBuilder = Tesselator.getInstance().getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);

        bufferBuilder.vertex(identity, 0,     0,      0).uv(0.0f, 0.0f).endVertex();
        bufferBuilder.vertex(identity, 0,     height, 0).uv(0.0f, 1.0f).endVertex();
        bufferBuilder.vertex(identity, width, height, 0).uv(1.0f, 1.0f).endVertex();
        bufferBuilder.vertex(identity, width, 0,      0).uv(1.0f, 0.0f).endVertex();

        BufferUploader.drawWithShader(bufferBuilder.end());

        RenderSystem.getModelViewStack().popPose();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.setProjectionMatrix(savedProjection, VertexSorting.ORTHOGRAPHIC_Z);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
    }
}
