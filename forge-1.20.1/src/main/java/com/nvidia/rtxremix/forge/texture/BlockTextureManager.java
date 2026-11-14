/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Map<ResourceLocation, SpriteEntry> spriteById = new ConcurrentHashMap<>();

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
            boolean transparent = false;
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.getModelManager() != null) {
                    TextureAtlas atlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
                    if (atlas != null) {
                        TextureAtlasSprite sprite = atlas.getSprite(loc);
                        if (sprite != null && sprite.contents() != null) {
                            NativeImage img = sprite.contents().getOriginalImage();
                            if (img != null) {
                                int w = sprite.contents().width();
                                int h = sprite.contents().height();
                                for (int y = 0; y < h && !transparent; y++) {
                                    for (int x = 0; x < w; x++) {
                                        int abgr = img.getPixelRGBA(x, y);
                                        int a = (abgr >> 24) & 0xFF;
                                        if (a < 250) {
                                            transparent = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            String path = loc.getPath();
            if (!transparent) {
                transparent = path.contains("leaves") || path.contains("sapling")
                        || path.contains("glass") || path.contains("water") || path.contains("door")
                        || path.contains("grass") || path.contains("flower")
                        || path.contains("torch")
                        || path.contains("plant") || path.contains("stem")
                        || path.contains("crop") || path.contains("rail")
                        || path.contains("chain") || path.contains("lantern")
                        || path.contains("iron_bars") || path.contains("ladder")
                        || path.contains("vine") || path.contains("lichen")
                        || path.contains("sugar_cane") || path.contains("fern")
                        || path.contains("mushroom") || path.contains("lily_pad")
                        || path.contains("nether_wart") || path.contains("coral")
                        || path.contains("scaffolding") || path.contains("campfire");
            }
            boolean sss = path.contains("leaves") || path.contains("plant") || path.contains("foliage");
            return new SpriteEntry(loc, transparent, sss);
        });
    }

    public Object getAtlasMaterialSSS(int layerType) {
        return null;
    }
}
