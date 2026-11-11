/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.light;

import net.minecraft.world.level.block.Block;

/**
 * Resolved block-attached light discovered during chunk iteration.
 */
public record LightEntry(
        BlockLightKey key,
        int lightLevel,
        Block block,
        LightShape shape,
        float x,
        float y,
        float z,
        float colorR,
        float colorG,
        float colorB,
        boolean hasColor,
        float intensity,
        boolean hasIntensity,
        float radius,
        float axisX,
        float axisY,
        float axisZ,
        float axisLength,
        float xAxisX,
        float xAxisY,
        float xAxisZ,
        float xSize,
        float yAxisX,
        float yAxisY,
        float yAxisZ,
        float ySize,
        float directionX,
        float directionY,
        float directionZ,
        boolean hasShaping,
        float shapingDirectionX,
        float shapingDirectionY,
        float shapingDirectionZ,
        float coneAngleDegrees,
        float coneSoftness,
        float focusExponent,
        float volumetricRadianceScale,
        float authoredIntensityMultiplier) {}
