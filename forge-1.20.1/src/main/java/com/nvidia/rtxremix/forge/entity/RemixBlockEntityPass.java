/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * Captures block-entity geometry where vanilla/Forge dispatches it and submits it through
 * the entity submitter with the BlockEntity as owner.
 */
public final class RemixBlockEntityPass {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixBlockEntityPass.class);

    private final RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
    private final IdentityHashMap<BlockEntityType<?>, String> typeKeyCache = new IdentityHashMap<>();

    private int frameCount = 0;

    public void beginFrame() {
        frameCount = 0;
    }

    @SuppressWarnings("unchecked")
    public <E extends BlockEntity> boolean capture(E be, BlockEntityRenderDispatcher dispatcher,
                                                   float tickDelta, RemixEntitySubmitter submitter) {
        if (be == null || !be.hasLevel() || dispatcher == null || submitter == null) {
            return false;
        }

        BlockEntityRenderer<E> beRenderer = dispatcher.getRenderer(be);
        if (beRenderer == null) {
            return false;
        }

        provider.beginEntity();
        provider.setBlockOrigin(be.getBlockPos());
        PoseStack matrices = new PoseStack();

        try {
            beRenderer.render(be, tickDelta, matrices, provider, 0x00F000F0, OverlayTexture.NO_OVERLAY);
            List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
            if (layers.isEmpty()) {
                return false;
            }

            EntityCaptureResult result = new EntityCaptureResult(
                    be,
                    Vec3.atLowerCornerOf(be.getBlockPos()),
                    0.0f,
                    layers,
                    null,
                    0,
                    typeKey(be),
                    false
            );
            submitter.submitEntity(result);
            frameCount++;
            return true;
        } catch (Exception e) {
            LOGGER.debug("Block entity capture failed for {}: {}", be.getBlockPos(), e.getMessage());
            return false;
        }
    }

    private String typeKey(BlockEntity be) {
        return typeKeyCache.computeIfAbsent(be.getType(),
                type -> BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type).toString());
    }

    public int getFrameCount() {
        return frameCount;
    }
}
