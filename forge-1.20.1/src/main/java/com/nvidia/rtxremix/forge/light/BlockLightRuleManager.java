/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.light;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages block-attached light rules for RTX Remix.
 */
public final class BlockLightRuleManager {
    public static final float RADIANCE_PER_LIGHT_LEVEL = 5.0f;

    public interface ModelSampler {
        float[] centroid();
    }

    private BlockLightRuleManager() {}

    public static boolean hasMatchingRules(BlockState state) {
        if (state == null || state.isAir()) return false;
        return state.getLightEmission() > 0;
    }

    public static List<LightEntry> resolve(BlockState state, BlockPos pos, ModelSampler sampler) {
        if (state == null || state.isAir()) return Collections.emptyList();
        int emission = state.getLightEmission();
        if (emission <= 0) return Collections.emptyList();

        float[] center = sampler != null ? sampler.centroid() : new float[]{0.5f, 0.5f, 0.5f};
        float worldX = pos.getX() + center[0];
        float worldY = pos.getY() + center[1];
        float worldZ = pos.getZ() + center[2];

        ResourceLocation ruleId = new ResourceLocation("minecraft", "emissive_block");
        BlockLightKey key = new BlockLightKey(pos.immutable(), ruleId, 0);

        List<LightEntry> list = new ArrayList<>(1);
        list.add(new LightEntry(
                key, emission, state.getBlock(), LightShape.SPHERE,
                worldX, worldY, worldZ,
                1.0f, 0.9f, 0.7f, true,
                emission * RADIANCE_PER_LIGHT_LEVEL, true,
                0.15f,
                0, 1, 0, 0,
                1, 0, 0, 0,
                0, 0, 1, 0,
                0, 0, 0,
                false, 0, 0, 0, 0, 0, 0,
                1.0f, 1.0f
        ));
        return list;
    }
}
