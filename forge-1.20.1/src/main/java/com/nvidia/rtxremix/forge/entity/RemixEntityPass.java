/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Coordinates entity capture for RTX Remix path tracing.
 */
public class RemixEntityPass {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixEntityPass.class);
    private static final int MAX_ENTITIES_PER_FRAME = 256;
    private static final double MAX_SWEEP_DISTANCE_SQ = 128.0 * 128.0;

    private final RemixVertexConsumerProvider provider = new RemixVertexConsumerProvider();
    private final Set<Integer> capturedEntityIds = new HashSet<>(MAX_ENTITIES_PER_FRAME);
    private final List<Entity> sweepCandidates = new ArrayList<>(MAX_ENTITIES_PER_FRAME);

    private boolean frameActive = false;
    private float[] reusableBoneData = new float[0];

    public void beginFrame(RemixEntitySubmitter submitter) {
        if (frameActive) return;
        frameActive = true;
        capturedEntityIds.clear();
        sweepCandidates.clear();
        submitter.beginFrame();
    }

    public void resetFrame() {
        frameActive = false;
        capturedEntityIds.clear();
        sweepCandidates.clear();
    }

    public boolean captureAndSubmit(Entity entity, EntityRenderDispatcher dispatcher,
                                    float tickDelta, RemixEntitySubmitter submitter) {
        if (!capturedEntityIds.add(entity.getId())) {
            return false;
        }

        boolean prevShadow = ((com.nvidia.rtxremix.forge.mixin.EntityRenderDispatcherAccessor) dispatcher).rtxremix$getRenderShadow();
        dispatcher.setRenderShadow(false);

        EntityCaptureResult result;
        try {
            result = renderOneEntity(entity, dispatcher, tickDelta);
        } catch (Exception e) {
            LOGGER.error("Error capturing entity {}: {}", entity.getType(), e.getMessage());
            capturedEntityIds.remove(entity.getId());
            return false;
        } finally {
            dispatcher.setRenderShadow(prevShadow);
        }

        if (result != null) {
            int submitted = submitter.submitEntity(result, Vec3.ZERO);
            if (submitted > 0) {
                return true;
            }
            LOGGER.warn("Remix failed to submit mesh instances for entity {}; falling back to OpenGL", entity.getType());
        }

        capturedEntityIds.remove(entity.getId());
        return false;
    }

    private EntityCaptureResult renderOneEntity(Entity entity, EntityRenderDispatcher dispatcher, float tickDelta) {
        Vec3 entityPos = entity.getPosition(tickDelta);
        float yaw = Mth.lerp(tickDelta, entity.yRotO, entity.getYRot());

        provider.beginEntity();
        EntityRenderContext ctx = EntityRenderContext.get();
        ctx.begin(true);

        PoseStack poseStack = new PoseStack();
        try {
            dispatcher.render(entity, 0.0, 0.0, 0.0, yaw, tickDelta, poseStack, provider, LightTexture.FULL_BRIGHT);
        } finally {
            ctx.reset();
        }

        List<RemixVertexConsumerProvider.CapturedLayer> layers = provider.endEntity();
        if (layers.isEmpty()) {
            return null;
        }

        int totalVertices = 0;
        for (RemixVertexConsumerProvider.CapturedLayer layer : layers) {
            totalVertices += layer.vertexCount();
        }
        if (totalVertices == 0) {
            return null;
        }
        // If entity is a complex mob/dragon but captured quads are negligible (e.g. only 1 accessory quad),
        // reject so vanilla OpenGL renders the complete mob rather than an incomplete/invisible body.
        if (entity instanceof net.minecraft.world.entity.Mob && totalVertices < 16) {
            LOGGER.debug("Entity {} captured only {} vertices; delegating to OpenGL", entity.getType(), totalVertices);
            return null;
        }

        reusableBoneData = ctx.snapshotBoneTransforms(reusableBoneData);
        int boneCount = ctx.getBoneCount();
        String typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();

        Minecraft client = Minecraft.getInstance();
        boolean isFirstPerson = entity == client.player && client.options.getCameraType().isFirstPerson();

        return new EntityCaptureResult(
                entity,
                entityPos,
                yaw,
                layers,
                reusableBoneData,
                boneCount,
                typeKey,
                isFirstPerson
        );
    }

    public void finishFrame(ClientLevel level, EntityRenderDispatcher dispatcher,
                            float tickDelta, RemixEntitySubmitter submitter, Camera camera) {
        if (!frameActive) return;

        if (level != null && camera != null && capturedEntityIds.size() < MAX_ENTITIES_PER_FRAME) {
            Vec3 camPos = camera.getPosition();
            sweepCandidates.clear();
            for (Entity entity : level.entitiesForRendering()) {
                if (!capturedEntityIds.contains(entity.getId())) {
                    if (entity.distanceToSqr(camPos) <= MAX_SWEEP_DISTANCE_SQ) {
                        sweepCandidates.add(entity);
                    }
                }
            }

            if (!sweepCandidates.isEmpty()) {
                sweepCandidates.sort((e1, e2) -> Double.compare(e1.distanceToSqr(camPos), e2.distanceToSqr(camPos)));
                for (int i = 0; i < sweepCandidates.size(); i++) {
                    if (capturedEntityIds.size() >= MAX_ENTITIES_PER_FRAME) {
                        break;
                    }
                    captureAndSubmit(sweepCandidates.get(i), dispatcher, tickDelta, submitter);
                }
                sweepCandidates.clear();
            }
        }

        submitter.endFrame();
        frameActive = false;
    }
}
