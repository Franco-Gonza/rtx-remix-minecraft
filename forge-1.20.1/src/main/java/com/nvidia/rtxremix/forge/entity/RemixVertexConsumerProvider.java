/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * MultiBufferSource provider used during RTX Remix entity capture passes.
 */
public class RemixVertexConsumerProvider implements MultiBufferSource {

    private RenderType[] activeLayers = new RenderType[8];
    private RemixCapturingConsumer[] activeConsumers = new RemixCapturingConsumer[8];
    private int activeCount;

    private final List<RemixCapturingConsumer> consumerPool = new ArrayList<>();
    private final List<CapturedLayer> resultBuffer = new ArrayList<>();

    private int originX, originY, originZ;

    public void beginEntity() {
        for (int i = 0; i < activeCount; i++) {
            RemixCapturingConsumer consumer = activeConsumers[i];
            consumer.clear();
            consumerPool.add(consumer);
            activeConsumers[i] = null;
            activeLayers[i] = null;
        }
        activeCount = 0;
        originX = 0;
        originY = 0;
        originZ = 0;
    }

    public void setBlockOrigin(BlockPos pos) {
        originX = pos.getX();
        originY = pos.getY();
        originZ = pos.getZ();
    }

    public List<CapturedLayer> endEntity() {
        resultBuffer.clear();
        for (int i = 0; i < activeCount; i++) {
            RemixCapturingConsumer consumer = activeConsumers[i];
            consumer.finishOpenVertex();
            if (consumer.getVertexCount() > 0) {
                CapturedLayer layer = consumer.capturedLayer();
                layer.bind(activeLayers[i]);
                resultBuffer.add(layer);
            }
        }
        return resultBuffer;
    }

    @Override
    public VertexConsumer getBuffer(RenderType layer) {
        for (int i = 0; i < activeCount; i++) {
            if (activeLayers[i].equals(layer)) {
                return activeConsumers[i];
            }
        }

        Optional<ResourceLocation> texOpt = RenderLayerUtils.capturableTexture(layer);
        ResourceLocation textureId;
        if (texOpt.isPresent()) {
            textureId = texOpt.get();
        } else if (RenderLayerUtils.isCapturableUntexturedLayer(layer)) {
            textureId = RenderLayerUtils.UNTEXTURED_LAYER_ID;
        } else {
            return NoOpVertexConsumer.INSTANCE;
        }

        RenderLayerUtils.Topology topology = RenderLayerUtils.topology(layer)
                .orElse(RenderLayerUtils.Topology.QUADS);
        boolean transientGeometry = RenderLayerUtils.isStreamedPerFrame(layer);
        RemixCapturingConsumer consumer;
        if (!consumerPool.isEmpty()) {
            consumer = consumerPool.remove(consumerPool.size() - 1);
            consumer.reinit(textureId, true, topology, transientGeometry);
        } else {
            consumer = new RemixCapturingConsumer(textureId, true, topology, transientGeometry);
        }
        consumer.setLayerIndex(activeCount);

        if (activeCount == activeLayers.length) {
            activeLayers = Arrays.copyOf(activeLayers, activeCount * 2);
            activeConsumers = Arrays.copyOf(activeConsumers, activeCount * 2);
        }
        activeLayers[activeCount] = layer;
        activeConsumers[activeCount] = consumer;
        activeCount++;
        return consumer;
    }

    public static final class CapturedLayer {
        private final RemixCapturingConsumer consumer;
        private RenderType renderLayer;

        public CapturedLayer(RemixCapturingConsumer consumer) {
            this.consumer = consumer;
        }

        public void bind(RenderType renderLayer) {
            this.renderLayer = renderLayer;
        }

        public ResourceLocation textureId() { return consumer.getTextureId(); }
        public RenderType renderLayer() { return renderLayer; }
        public RemixCapturingConsumer consumer() { return consumer; }
        public int vertexCount() { return consumer.getVertexCount(); }
        public int layerIndex() { return consumer.getLayerIndex(); }
        public RenderLayerUtils.Topology topology() { return consumer.getTopology(); }
        public boolean transientGeometry() { return consumer.isTransientGeometry(); }
    }

    private static class NoOpVertexConsumer implements VertexConsumer {
        static final NoOpVertexConsumer INSTANCE = new NoOpVertexConsumer();
        @Override public VertexConsumer vertex(double x, double y, double z) { return this; }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() {}
        @Override public void defaultColor(int r, int g, int b, int a) {}
        @Override public void unsetDefaultColor() {}
    }
}
