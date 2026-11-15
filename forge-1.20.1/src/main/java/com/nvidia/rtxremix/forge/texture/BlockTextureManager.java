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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-sprite textures, material categories, and PBR mappings for RTX Remix terrain on Forge 1.20.1.
 */
public class BlockTextureManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockTextureManager.class);

    public static final int L_SOLID = 0;
    public static final int L_CUTOUT = 1;
    public static final int L_CUTOUT_MIPPED = 2;
    public static final int L_TRANS = 3;
    public static final int L_GLASS = 4;
    public static final int L_LAVA = 5;
    public static final int LAYER_COUNT = 6;

    public static final float WATER_TILE_SIZE = 10.0f;

    public record SpriteEntry(ResourceLocation id, boolean hasTransparency, boolean hasSSS) {
        public boolean hasTransparency(float u0, float v0, float u1, float v1) {
            return hasTransparency;
        }
    }

    public record PbrAtlasResult(
            byte[] normalAtlas,
            byte[] heightAtlas,
            byte[] roughnessAtlas,
            byte[] metallicAtlas,
            byte[] emissiveAtlas,
            boolean hasNormal,
            boolean hasHeight,
            boolean hasRoughness,
            boolean hasMetallic,
            boolean hasEmissive
    ) {}

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
            case "lava" -> L_LAVA;
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
                        || path.contains("grass") || path.contains("flower") || path.contains("bush")
                        || path.contains("shrub") || path.contains("dead_bush") || path.contains("deadbush")
                        || path.contains("cactus") || path.contains("branch") || path.contains("twig")
                        || path.contains("foliage") || path.contains("flora") || path.contains("wildflower")
                        || path.contains("weed") || path.contains("clover") || path.contains("bamboo")
                        || path.contains("poppy") || path.contains("tulip") || path.contains("dandelion")
                        || path.contains("daisy") || path.contains("orchid") || path.contains("allium")
                        || path.contains("cornflower") || path.contains("bluet") || path.contains("sunflower")
                        || path.contains("peony") || path.contains("rose") || path.contains("lilac")
                        || path.contains("blossom") || path.contains("roots") || path.contains("sprouts")
                        || path.contains("fungus") || path.contains("azalea")
                        || path.contains("torch")
                        || path.contains("plant") || path.contains("stem")
                        || path.contains("crop") || path.contains("rail")
                        || path.contains("chain") || path.contains("lantern")
                        || path.contains("iron_bars") || path.contains("ladder")
                        || path.contains("vine") || path.contains("lichen")
                        || path.contains("sugar_cane") || path.contains("fern")
                        || path.contains("mushroom") || path.contains("lily_pad")
                        || path.contains("nether_wart") || path.contains("coral")
                        || path.contains("scaffolding") || path.contains("campfire")
                        || path.contains("amethyst") || path.contains("dripstone")
                        || path.contains("trapdoor") || path.contains("bars")
                        || path.contains("seagrass") || path.contains("kelp")
                        || path.contains("berry") || path.contains("moss")
                        || path.contains("hanging") || path.contains("tendril")
                        || path.contains("petal") || path.contains("canopy")
                        || path.contains("needle") || path.contains("steppe");
            }
            boolean sss = path.contains("leaves") || path.contains("plant") || path.contains("foliage") || path.contains("bush");
            return new SpriteEntry(loc, transparent, sss);
        });
    }

    public Object getAtlasMaterialSSS(int layerType) {
        return null;
    }

    /**
     * Builds parallel LabPBR atlas byte buffers (_n, _s, _sss) from resource pack sidecars.
     */
    public PbrAtlasResult buildPbrAtlas(TextureAtlas atlas, int atlasW, int atlasH, byte[] albedoRGBA) {
        int totalPixels = atlasW * atlasH;
        byte[] normalAtlas = new byte[totalPixels * 4];
        byte[] heightAtlas = new byte[totalPixels];
        byte[] roughnessAtlas = new byte[totalPixels];
        byte[] metallicAtlas = new byte[totalPixels];
        byte[] emissiveAtlas = new byte[totalPixels * 4];

        // Initialize flat normal: (128, 128, 255, 255)
        for (int i = 0; i < totalPixels; i++) {
            int idx4 = i * 4;
            normalAtlas[idx4]     = (byte) 128;
            normalAtlas[idx4 + 1] = (byte) 128;
            normalAtlas[idx4 + 2] = (byte) 255;
            normalAtlas[idx4 + 3] = (byte) 255;
        }

        // Initialize height to 255 (top of parallax surface)
        Arrays.fill(heightAtlas, (byte) 255);

        // Anti-mirroring safeguard: initialize default roughness to 204 (~0.80 roughness)
        // so ordinary non-PBR surfaces stay matte dielectric.
        Arrays.fill(roughnessAtlas, (byte) 204);

        // Initialize metallic to 0 (dielectric)
        Arrays.fill(metallicAtlas, (byte) 0);

        boolean hasNormal = false;
        boolean hasHeight = false;
        boolean hasRoughness = false;
        boolean hasMetallic = false;
        boolean hasEmissive = false;
        int pbrSpriteCount = 0;

        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getResourceManager() != null && atlas != null) {
            LabPBRConverter.VariantCache cache = LabPBRConverter.buildVariantCache(mc.getResourceManager());
            for (ResourceLocation loc : atlas.getTextureLocations()) {
                TextureAtlasSprite sprite = atlas.getSprite(loc);
                if (sprite == null || sprite.contents() == null) continue;

                int ox = sprite.getX();
                int oy = sprite.getY();
                int w = sprite.contents().width();
                int h = sprite.contents().height();

                NativeImage nImg = cache.loadVariant(loc, "_n");
                NativeImage sImg = cache.loadVariant(loc, "_s");
                NativeImage sssImg = cache.loadVariant(loc, "_sss");

                boolean spriteHasPbr = false;
                try {
                    if (nImg != null) {
                        LabPBRConverter.blitNormal(normalAtlas, atlasW, nImg, ox, oy, w, h);
                        LabPBRConverter.blitHeight(heightAtlas, atlasW, nImg, ox, oy, w, h);
                        if (albedoRGBA != null) {
                            LabPBRConverter.applyAO(albedoRGBA, atlasW, nImg, ox, oy, w, h);
                        }
                        hasNormal = true;
                        hasHeight = true;
                        spriteHasPbr = true;
                    }

                    if (sImg != null) {
                        LabPBRConverter.blitRoughness(roughnessAtlas, atlasW, sImg, ox, oy, w, h);
                        LabPBRConverter.blitMetallic(metallicAtlas, atlasW, sImg, ox, oy, w, h);
                        hasRoughness = true;
                        hasMetallic = true;
                        spriteHasPbr = true;

                        if (LabPBRConverter.hasEmissiveData(sImg) && albedoRGBA != null) {
                            LabPBRConverter.blitEmissive(emissiveAtlas, atlasW, albedoRGBA, sImg, ox, oy, w, h, 0.0f, 1.0f);
                            hasEmissive = true;
                        }
                    }
                } finally {
                    if (nImg != null) nImg.close();
                    if (sImg != null) sImg.close();
                    if (sssImg != null) sssImg.close();
                }

                if (spriteHasPbr) {
                    pbrSpriteCount++;
                }
            }
        }

        LOGGER.info("Built LabPBR terrain atlas ({} sprites with PBR sidecars, normal={}, height={}, roughness={}, metallic={}, emissive={})",
                pbrSpriteCount, hasNormal, hasHeight, hasRoughness, hasMetallic, hasEmissive);

        return new PbrAtlasResult(normalAtlas, heightAtlas, roughnessAtlas, metallicAtlas, emissiveAtlas,
                hasNormal, hasHeight, hasRoughness, hasMetallic, hasEmissive);
    }
}
