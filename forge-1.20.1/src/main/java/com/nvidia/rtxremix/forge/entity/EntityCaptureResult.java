/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Result of capturing an entity's rendered model for RTX Remix submission.
 */
public record EntityCaptureResult(
        Object entity,
        Vec3 worldPos,
        float yaw,
        List<RemixVertexConsumerProvider.CapturedLayer> layers,
        float[] boneData,
        int boneCount,
        String entityTypeKey,
        boolean firstPersonLocalPlayer
) {}
