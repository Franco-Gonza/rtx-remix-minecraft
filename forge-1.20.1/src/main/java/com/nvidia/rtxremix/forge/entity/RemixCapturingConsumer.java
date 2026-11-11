/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Captures vertex data for entities rendered for RTX Remix path tracing.
 * Uses SoA buffers with MAX_VERTICES = 16384 to support high-poly dragon models.
 */
public class RemixCapturingConsumer implements VertexConsumer {

    public static final int MAX_VERTICES = 16384;

    private ResourceLocation textureId;
    private String renderLayerName = "solid";
    private RenderLayerUtils.Topology topology = RenderLayerUtils.Topology.QUADS;
    private boolean transientGeometry = false;
    private int layerIndex = 0;

    private int vertexCount = 0;
    private final float[] px = new float[MAX_VERTICES];
    private final float[] py = new float[MAX_VERTICES];
    private final float[] pz = new float[MAX_VERTICES];
    private final float[] nx = new float[MAX_VERTICES];
    private final float[] ny = new float[MAX_VERTICES];
    private final float[] nz = new float[MAX_VERTICES];
    private final float[] tu = new float[MAX_VERTICES];
    private final float[] tv = new float[MAX_VERTICES];
    private final int[] colors = new int[MAX_VERTICES];
    private final int[] boneIndices = new int[MAX_VERTICES];

    // Current vertex staging
    private float curX, curY, curZ;
    private float curNx, curNy, curNz;
    private float curU, curV;
    private int curColor = 0xFFFFFFFF;
    private int curOverlay = OverlayTexture.NO_OVERLAY;
    private int curBoneIndex = -1;
    private boolean vertexOpen = false;

    private final RemixVertexConsumerProvider.CapturedLayer capturedLayer =
            new RemixVertexConsumerProvider.CapturedLayer(this);

    public RemixCapturingConsumer() {}

    public RemixCapturingConsumer(ResourceLocation textureId, boolean clear,
                                  RenderLayerUtils.Topology topology, boolean transientGeometry) {
        reinit(textureId, clear, topology, transientGeometry);
    }

    public void reinit(ResourceLocation textureId, boolean clear,
                       RenderLayerUtils.Topology topology, boolean transientGeometry) {
        this.textureId = textureId;
        this.topology = topology;
        this.transientGeometry = transientGeometry;
        this.vertexCount = 0;
        this.curBoneIndex = -1;
        this.vertexOpen = false;
    }

    public void clear() {
        this.textureId = null;
        this.vertexCount = 0;
        this.curBoneIndex = -1;
        this.vertexOpen = false;
    }

    public void reset(ResourceLocation textureId, String renderLayerName) {
        this.textureId = textureId;
        this.renderLayerName = renderLayerName;
        this.vertexCount = 0;
        this.curBoneIndex = -1;
        this.vertexOpen = false;
    }

    public void finishOpenVertex() {
        if (vertexOpen) {
            endVertex();
        }
    }

    public RemixVertexConsumerProvider.CapturedLayer capturedLayer() {
        return capturedLayer;
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public ResourceLocation getTextureId() {
        return textureId;
    }

    public String getRenderLayerName() {
        return renderLayerName;
    }

    public RenderLayerUtils.Topology getTopology() {
        return topology;
    }

    public boolean isTransientGeometry() {
        return transientGeometry;
    }

    public int getLayerIndex() {
        return layerIndex;
    }

    public void setLayerIndex(int layerIndex) {
        this.layerIndex = layerIndex;
    }

    public float[] getPositionsX() { return px; }
    public float[] getPositionsY() { return py; }
    public float[] getPositionsZ() { return pz; }
    public float[] getNormalsX() { return nx; }
    public float[] getNormalsY() { return ny; }
    public float[] getNormalsZ() { return nz; }
    public float[] getUvsU() { return tu; }
    public float[] getUvsV() { return tv; }
    public int[] getColors() { return colors; }
    public int[] getBoneIndices() { return boneIndices; }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        finishOpenVertex();
        this.curX = (float) x;
        this.curY = (float) y;
        this.curZ = (float) z;
        this.vertexOpen = true;
        return this;
    }

    @Override
    public VertexConsumer vertex(Matrix4f matrix, float x, float y, float z) {
        finishOpenVertex();
        EntityRenderContext ctx = EntityRenderContext.get();
        if (ctx.isActive()) {
            this.curBoneIndex = ctx.pushBone(matrix);
        }
        this.curX = x;
        this.curY = y;
        this.curZ = z;
        this.vertexOpen = true;
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        this.curColor = ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16) | ((green & 0xFF) << 8) | (blue & 0xFF);
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        this.curU = u;
        this.curV = v;
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        this.curOverlay = (u & 0xFFFF) | ((v & 0xFFFF) << 16);
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        this.curNx = x;
        this.curNy = y;
        this.curNz = z;
        return this;
    }

    @Override
    public VertexConsumer normal(Matrix3f normalMatrix, float x, float y, float z) {
        this.curNx = x;
        this.curNy = y;
        this.curNz = z;
        return this;
    }

    @Override
    public void endVertex() {
        if (vertexCount >= MAX_VERTICES) {
            vertexOpen = false;
            return;
        }

        int i = vertexCount++;
        px[i] = curX;
        py[i] = curY;
        pz[i] = curZ;
        nx[i] = curNx;
        ny[i] = curNy;
        nz[i] = curNz;
        tu[i] = curU;
        tv[i] = curV;
        colors[i] = curColor;
        boneIndices[i] = curBoneIndex >= 0 ? curBoneIndex : EntityRenderContext.get().currentBoneIndex();
        vertexOpen = false;
    }

    @Override
    public void defaultColor(int r, int g, int b, int a) {
        this.curColor = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    @Override
    public void unsetDefaultColor() {
        this.curColor = 0xFFFFFFFF;
    }
}
