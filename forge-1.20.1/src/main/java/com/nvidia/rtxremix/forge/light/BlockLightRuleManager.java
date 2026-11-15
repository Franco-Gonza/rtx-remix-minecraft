/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.light;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages block-attached light rules for RTX Remix on Forge 1.20.1.
 */
public final class BlockLightRuleManager {
    public static final float RADIANCE_PER_LIGHT_LEVEL = 5.0f;
    public static final float TORCH_AUTHORED_INTENSITY_MULTIPLIER = 7.5f;
    public static final float THIN_SPHERE_INTENSITY_SCALE = 3.0f;
    public static final float TORCH_RADIUS = 0.08f;

    public interface ModelSampler {
        float[] centroid();
    }

    private BlockLightRuleManager() {}

    public static boolean hasMatchingRules(BlockState state) {
        if (state == null || state.isAir()) return false;
        return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)
                || state.is(Blocks.REDSTONE_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.LANTERN) || state.is(Blocks.SOUL_LANTERN)
                || state.is(Blocks.END_ROD)
                || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE);
    }

    public static List<LightEntry> resolve(BlockState state, BlockPos pos, ModelSampler sampler) {
        if (state == null || state.isAir()) return Collections.emptyList();
        int emission = state.getLightEmission();
        if (emission <= 0) return Collections.emptyList();

        float x = 0.5f;
        float y = 0.5f;
        float z = 0.5f;
        float radius = 0.15f;
        float colorR = 1.0f;
        float colorG = 0.9f;
        float colorB = 0.7f;
        float intensityScale = 15.0f;
        String ruleName = "emissive_block";

        if (state.is(Blocks.TORCH)) {
            ruleName = "torch";
            x = 0.5f; y = 0.7f; z = 0.5f;
            radius = TORCH_RADIUS;
            colorR = 1.0f; colorG = 0.35f; colorB = 0.1f;
            intensityScale = THIN_SPHERE_INTENSITY_SCALE * TORCH_AUTHORED_INTENSITY_MULTIPLIER;
        } else if (state.is(Blocks.WALL_TORCH)) {
            ruleName = "wall_torch";
            Direction facing = state.getValue(WallTorchBlock.FACING).getOpposite();
            x = 0.5f + 0.27f * facing.getStepX();
            y = 0.92f;
            z = 0.5f + 0.27f * facing.getStepZ();
            radius = TORCH_RADIUS;
            colorR = 1.0f; colorG = 0.35f; colorB = 0.1f;
            intensityScale = THIN_SPHERE_INTENSITY_SCALE * TORCH_AUTHORED_INTENSITY_MULTIPLIER;
        } else if (state.is(Blocks.SOUL_TORCH)) {
            ruleName = "soul_torch";
            x = 0.5f; y = 0.7f; z = 0.5f;
            radius = TORCH_RADIUS;
            colorR = 0.05f; colorG = 0.2f; colorB = 1.0f;
            intensityScale = THIN_SPHERE_INTENSITY_SCALE * TORCH_AUTHORED_INTENSITY_MULTIPLIER;
        } else if (state.is(Blocks.SOUL_WALL_TORCH)) {
            ruleName = "soul_wall_torch";
            Direction facing = state.getValue(WallTorchBlock.FACING).getOpposite();
            x = 0.5f + 0.27f * facing.getStepX();
            y = 0.92f;
            z = 0.5f + 0.27f * facing.getStepZ();
            radius = TORCH_RADIUS;
            colorR = 0.05f; colorG = 0.2f; colorB = 1.0f;
            intensityScale = THIN_SPHERE_INTENSITY_SCALE * TORCH_AUTHORED_INTENSITY_MULTIPLIER;
        } else if (state.is(Blocks.REDSTONE_TORCH)) {
            ruleName = "redstone_torch";
            x = 0.5f; y = 0.7f; z = 0.5f;
            radius = 0.07f;
            colorR = 1.0f; colorG = 0.15f; colorB = 0.02f;
            intensityScale = THIN_SPHERE_INTENSITY_SCALE * TORCH_AUTHORED_INTENSITY_MULTIPLIER;
        } else if (state.is(Blocks.REDSTONE_WALL_TORCH)) {
            ruleName = "redstone_wall_torch";
            Direction facing = state.getValue(RedstoneWallTorchBlock.FACING).getOpposite();
            x = 0.5f + 0.27f * facing.getStepX();
            y = 0.92f;
            z = 0.5f + 0.27f * facing.getStepZ();
            radius = 0.07f;
            colorR = 1.0f; colorG = 0.15f; colorB = 0.02f;
            intensityScale = THIN_SPHERE_INTENSITY_SCALE * TORCH_AUTHORED_INTENSITY_MULTIPLIER;
        } else if (state.is(Blocks.CAMPFIRE)) {
            ruleName = "campfire";
            x = 0.5f; y = 0.55f; z = 0.5f;
            radius = 0.25f;
            colorR = 1.0f; colorG = 0.15f; colorB = 0.05f;
            intensityScale = 1.5f;
        } else if (state.is(Blocks.SOUL_CAMPFIRE)) {
            ruleName = "soul_campfire";
            x = 0.5f; y = 0.55f; z = 0.5f;
            radius = 0.25f;
            colorR = 0.05f; colorG = 0.2f; colorB = 1.0f;
            intensityScale = 1.5f;
        } else if (state.is(Blocks.LANTERN)) {
            ruleName = "lantern";
            boolean hanging = state.hasProperty(LanternBlock.HANGING) && state.getValue(LanternBlock.HANGING);
            x = 0.5f; y = hanging ? 0.35f : 0.25f; z = 0.5f;
            radius = 0.10f;
            colorR = 1.0f; colorG = 0.75f; colorB = 0.4f;
            intensityScale = 1.0f;
        } else if (state.is(Blocks.SOUL_LANTERN)) {
            ruleName = "soul_lantern";
            boolean hanging = state.hasProperty(LanternBlock.HANGING) && state.getValue(LanternBlock.HANGING);
            x = 0.5f; y = hanging ? 0.35f : 0.25f; z = 0.5f;
            radius = 0.10f;
            colorR = 0.05f; colorG = 0.2f; colorB = 1.0f;
            intensityScale = 1.0f;
        } else if (state.is(Blocks.END_ROD)) {
            ruleName = "end_rod";
            x = 0.5f; y = 0.5f; z = 0.5f;
            radius = 0.08f;
            colorR = 0.95f; colorG = 0.95f; colorB = 1.0f;
            intensityScale = 1.0f;
        } else if (state.is(Blocks.FIRE)) {
            ruleName = "fire";
            x = 0.5f; y = 0.35f; z = 0.5f;
            radius = 0.25f;
            colorR = 1.0f; colorG = 0.45f; colorB = 0.1f;
            intensityScale = 2.0f;
        } else if (state.is(Blocks.SOUL_FIRE)) {
            ruleName = "soul_fire";
            x = 0.5f; y = 0.35f; z = 0.5f;
            radius = 0.25f;
            colorR = 0.05f; colorG = 0.35f; colorB = 1.0f;
            intensityScale = 2.0f;
        } else {
            return Collections.emptyList();
        }

        float worldX = pos.getX() + x;
        float worldY = pos.getY() + y;
        float worldZ = pos.getZ() + z;

        ResourceLocation ruleId = new ResourceLocation("minecraft", ruleName);
        BlockLightKey key = new BlockLightKey(pos.immutable(), ruleId, 0);

        List<LightEntry> list = new ArrayList<>(1);
        list.add(new LightEntry(
                key, emission, state.getBlock(), LightShape.SPHERE,
                worldX, worldY, worldZ,
                colorR, colorG, colorB, true,
                emission * RADIANCE_PER_LIGHT_LEVEL * intensityScale, true,
                radius,
                0, 1, 0, 0,
                1, 0, 0, 0,
                0, 0, 1, 0,
                0, 0, 0,
                false, 0, 0, 0, 0, 0, 0,
                0.5f, 1.0f
        ));
        return list;
    }
}
