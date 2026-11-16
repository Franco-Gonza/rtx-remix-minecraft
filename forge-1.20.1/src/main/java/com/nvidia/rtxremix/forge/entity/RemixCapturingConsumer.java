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

    public static final int INITIAL_CAPACITY = 16384;
    public static final int MAX_VERTICES = INITIAL_CAPACITY;

    private ResourceLocation textureId;
    private String renderLayerName = "solid";
    private RenderLayerUtils.Topology topology = RenderLayerUtils.Topology.QUADS;
    private boolean transientGeometry = false;
    private int layerIndex = 0;

    private int capacity = INITIAL_CAPACITY;
    private int vertexCount = 0;
    private float[] px = new float[capacity];
    private float[] py = new float[capacity];
    private float[] pz = new float[capacity];
    private float[] nx = new float[capacity];
    private float[] ny = new float[capacity];
    private float[] nz = new float[capacity];
    private float[] tu = new float[capacity];
    private float[] tv = new float[capacity];
    private int[] colors = new int[capacity];
    private int[] boneIndices = new int[capacity];

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
        float tx = matrix.m00() * x + matrix.m10() * y + matrix.m20() * z + matrix.m30();
        float ty = matrix.m01() * x + matrix.m11() * y + matrix.m21() * z + matrix.m31();
        float tz = matrix.m02() * x + matrix.m12() * y + matrix.m22() * z + matrix.m32();
        this.curX = tx;
        this.curY = ty;
        this.curZ = tz;
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
        float tnx = normalMatrix.m00() * x + normalMatrix.m10() * y + normalMatrix.m20() * z;
        float tny = normalMatrix.m01() * x + normalMatrix.m11() * y + normalMatrix.m21() * z;
        float tnz = normalMatrix.m02() * x + normalMatrix.m12() * y + normalMatrix.m22() * z;
        float lenSq = tnx * tnx + tny * tny + tnz * tnz;
        if (lenSq > 1.0E-6f) {
            float inv = (float) (1.0 / Math.sqrt(lenSq));
            tnx *= inv;
            tny *= inv;
            tnz *= inv;
        }
        this.curNx = tnx;
        this.curNy = tny;
        this.curNz = tnz;
        return this;
    }

    private void ensureCapacity(int minCapacity) {
        int newCap = Math.max(minCapacity, capacity * 2);
        px = java.util.Arrays.copyOf(px, newCap);
        py = java.util.Arrays.copyOf(py, newCap);
        pz = java.util.Arrays.copyOf(pz, newCap);
        nx = java.util.Arrays.copyOf(nx, newCap);
        ny = java.util.Arrays.copyOf(ny, newCap);
        nz = java.util.Arrays.copyOf(nz, newCap);
        tu = java.util.Arrays.copyOf(tu, newCap);
        tv = java.util.Arrays.copyOf(tv, newCap);
        colors = java.util.Arrays.copyOf(colors, newCap);
        boneIndices = java.util.Arrays.copyOf(boneIndices, newCap);
        capacity = newCap;
    }

    @Override
    public void endVertex() {
        if (vertexCount >= capacity) {
            ensureCapacity(capacity + 1024);
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
    public void vertex(float x, float y, float z, float red, float green, float blue, float alpha,
                       float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
        finishOpenVertex();
        if (vertexCount >= capacity) {
            ensureCapacity(capacity + 1024);
        }
        int i = vertexCount++;
        px[i] = x;
        py[i] = y;
        pz[i] = z;
        nx[i] = normalX;
        ny[i] = normalY;
        nz[i] = normalZ;
        tu[i] = u;
        tv[i] = v;
        colors[i] = (((int)(alpha * 255.0f) & 0xFF) << 24)
                | (((int)(red * 255.0f) & 0xFF) << 16)
                | (((int)(green * 255.0f) & 0xFF) << 8)
                | ((int)(blue * 255.0f) & 0xFF);
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
