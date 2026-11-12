/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages per-sprite textures, material categories, and PBR mappings for RTX Remix terrain on Forge 1.20.1.
 */
public class BlockTextureManager {

    public static final int L_SOLID = 0;
    public static final int L_CUTOUT = 1;
    public static final int L_CUTOUT_MIPPED = 2;
    public static final int L_TRANS = 3;
    public static final int L_GLASS = 4;
    public static final int LAYER_COUNT = 5;

    public static final float WATER_TILE_SIZE = 10.0f;

    public record SpriteEntry(ResourceLocation id, boolean hasTransparency, boolean hasSSS) {
        public boolean hasTransparency(float u0, float v0, float u1, float v1) {
            return hasTransparency;
        }
    }

    private static BlockTextureManager INSTANCE = new BlockTextureManager();
    private final Map<ResourceLocation, SpriteEntry> spriteById = new HashMap<>();

    public BlockTextureManager() {
        INSTANCE = this;
    }

    public static BlockTextureManager get() {
        return INSTANCE;
    }

    public static float getWaterTileSize() {
        return WATER_TILE_SIZE;
    }

    public static int layerType(String layer) {
        if (layer == null) return L_SOLID;
        return switch (layer) {
            case "cutout" -> L_CUTOUT;
            case "cutout_mipped" -> L_CUTOUT_MIPPED;
            case "translucent" -> L_TRANS;
            case "glass" -> L_GLASS;
            default -> L_SOLID;
        };
    }

    public SpriteEntry getSpriteById(ResourceLocation id) {
        if (id == null) return null;
        return spriteById.computeIfAbsent(id, loc -> {
            String path = loc.getPath();
            boolean transparent = path.contains("leaves") || path.contains("sapling")
                    || path.contains("glass") || path.contains("water") || path.contains("door")
                    || path.contains("grass") || path.contains("flower");
            boolean sss = path.contains("leaves") || path.contains("plant") || path.contains("foliage");
            return new SpriteEntry(loc, transparent, sss);
        });
    }

    public Object getAtlasMaterialSSS(int layerType) {
        return null;
    }
}
