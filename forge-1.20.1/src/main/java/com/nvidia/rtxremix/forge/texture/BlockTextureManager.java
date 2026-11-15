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

    public static final class AnimatedSpriteLabPBR {
        public final byte[] normalTable;      // RGBA8 (4 bytes/pix)
        public final byte[] aoTable;          // 1 byte/pix (AO from _n.B)
        public final byte[] heightTable;      // R8 (1 byte/pix from _n.A)
        public final int nW, nH;
        public final byte[] sRoughnessTable;  // R8 (1 byte/pix from _s.R)
        public final byte[] sMetallicTable;   // R8 (1 byte/pix from _s.G)
        public final byte[] sAlphaTable;      // 1 byte/pix (emissive factor from _s.A)
        public final byte[] sSssTable;        // 1 byte/pix (SSS intensity from _s.B)
        public final int sW, sH;
        public final byte[] sssAlbedoTable;   // RGBA8 (4 bytes/pix from _sss)
        public final int sssW, sssH;
        public final boolean hasEmissive;
        public final boolean hasSSS;
        public final boolean hasSssAlbedo;

        public AnimatedSpriteLabPBR(byte[] normalTable, byte[] aoTable, byte[] heightTable, int nW, int nH,
                                   byte[] sRoughnessTable, byte[] sMetallicTable, byte[] sAlphaTable, byte[] sSssTable, int sW, int sH,
                                   byte[] sssAlbedoTable, int sssW, int sssH,
                                   boolean hasEmissive, boolean hasSSS, boolean hasSssAlbedo) {
            this.normalTable = normalTable;
            this.aoTable = aoTable;
            this.heightTable = heightTable;
            this.nW = nW;
            this.nH = nH;
            this.sRoughnessTable = sRoughnessTable;
            this.sMetallicTable = sMetallicTable;
            this.sAlphaTable = sAlphaTable;
            this.sSssTable = sSssTable;
            this.sW = sW;
            this.sH = sH;
            this.sssAlbedoTable = sssAlbedoTable;
            this.sssW = sssW;
            this.sssH = sssH;
            this.hasEmissive = hasEmissive;
            this.hasSSS = hasSSS;
            this.hasSssAlbedo = hasSssAlbedo;
        }

        public AnimatedSpriteLabPBR(byte[] normalTable, byte[] aoTable, byte[] heightTable, int nW, int nH,
                                   byte[] sRoughnessTable, byte[] sMetallicTable, byte[] sAlphaTable, byte[] sSssTable, int sW, int sH,
                                   byte[] sssAlbedoTable, int sssW, int sssH,
                                   boolean hasEmissive, boolean hasSSS) {
            this(normalTable, aoTable, heightTable, nW, nH,
                 sRoughnessTable, sMetallicTable, sAlphaTable, sSssTable, sW, sH,
                 sssAlbedoTable, sssW, sssH, hasEmissive, hasSSS, sssAlbedoTable != null);
        }

        public AnimatedSpriteLabPBR(byte[] normalTable, byte[] aoTable, byte[] heightTable, int nW, int nH,
                                   byte[] sRoughnessTable, byte[] sMetallicTable, byte[] sAlphaTable, byte[] sSssTable, int sW, int sH,
                                   boolean hasEmissive, boolean hasSSS) {
            this(normalTable, aoTable, heightTable, nW, nH,
                 sRoughnessTable, sMetallicTable, sAlphaTable, sSssTable, sW, sH,
                 null, 0, 0, hasEmissive, hasSSS, false);
        }
    }

    public record PbrAtlasResult(
            byte[] normalAtlas,
            byte[] heightAtlas,
            byte[] roughnessAtlas,
            byte[] metallicAtlas,
            byte[] emissiveAtlas,
            byte[] sssAtlas,
            byte[] sssAlbedoAtlas,
            boolean hasNormal,
            boolean hasHeight,
            boolean hasRoughness,
            boolean hasMetallic,
            boolean hasEmissive,
            boolean hasSSS
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

    private final Long[] atlasMaterialsSSS = new Long[6];

    public void setAtlasMaterialSSS(int layerType, Long handle) {
        if (layerType >= 0 && layerType < atlasMaterialsSSS.length) {
            atlasMaterialsSSS[layerType] = handle;
        }
    }

    public void clearAtlasMaterialsSSS() {
        Arrays.fill(atlasMaterialsSSS, null);
    }

    public Long getAtlasMaterialSSS(int layerType) {
        if (layerType >= 0 && layerType < atlasMaterialsSSS.length && atlasMaterialsSSS[layerType] != null) {
            return atlasMaterialsSSS[layerType];
        }
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
        byte[] sssAtlas = new byte[totalPixels]; // R8_UNORM (1 byte per pixel: thickness/intensity)
        byte[] sssAlbedoAtlas = new byte[totalPixels * 4]; // RGBA8_UNORM (4 bytes per pixel: scattering albedo)

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

        // Initialize SSS thickness to 191 (~0.75 default transmission distance) and SSS albedo
        byte defaultThickness = (byte) Math.round(0.75f * 255.0f);
        Arrays.fill(sssAtlas, defaultThickness);
        if (albedoRGBA != null) {
            System.arraycopy(albedoRGBA, 0, sssAlbedoAtlas, 0, Math.min(albedoRGBA.length, sssAlbedoAtlas.length));
        }

        boolean hasNormal = false;
        boolean hasHeight = false;
        boolean hasRoughness = false;
        boolean hasMetallic = false;
        boolean hasEmissive = false;
        boolean hasSSS = false;
        int pbrSpriteCount = 0;
        Map<ResourceLocation, AnimatedSpriteLabPBR> animatedPbrMap = new java.util.HashMap<>();

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
                        LabPBRConverter.applyAO(sssAlbedoAtlas, atlasW, nImg, ox, oy, w, h);
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

                        if (LabPBRConverter.hasSSSData(sImg)) {
                            LabPBRConverter.blitSSS(sssAtlas, atlasW, sImg, ox, oy, w, h);
                            hasSSS = true;
                        }
                    }

                    if (sssImg != null) {
                        LabPBRConverter.blitSingleScatteringAlbedo(sssAlbedoAtlas, atlasW, sssImg, ox, oy, w, h);
                        hasSSS = true;
                        spriteHasPbr = true;
                    }

                    if (spriteHasPbr && sprite.contents() != null && sprite.contents().getOriginalImage() != null) {
                        int origH = sprite.contents().getOriginalImage().getHeight();
                        int frameH = sprite.contents().height();
                        if (origH > frameH) {
                            byte[] normTab = (nImg != null) ? LabPBRConverter.buildNormalTable(nImg) : null;
                            byte[] aoTab = (nImg != null) ? LabPBRConverter.buildAOTable(nImg) : null;
                            byte[] heightTab = (nImg != null) ? LabPBRConverter.buildHeightTable(nImg) : null;
                            int nw = (nImg != null) ? nImg.getWidth() : 0;
                            int nh = (nImg != null) ? nImg.getHeight() : 0;

                            byte[] rTab = (sImg != null) ? LabPBRConverter.buildSRoughnessTable(sImg) : null;
                            byte[] mTab = (sImg != null) ? LabPBRConverter.buildSMetallicTable(sImg) : null;
                            byte[] aTab = (sImg != null) ? LabPBRConverter.buildSAlphaTable(sImg) : null;
                            byte[] sssTab = (sImg != null) ? LabPBRConverter.buildSSssTable(sImg) : null;
                            int sw = (sImg != null) ? sImg.getWidth() : 0;
                            int sh = (sImg != null) ? sImg.getHeight() : 0;

                            byte[] sssAlbedoTab = (sssImg != null) ? LabPBRConverter.buildSingleScatteringAlbedoTable(sssImg) : null;
                            int sssw = (sssImg != null) ? sssImg.getWidth() : 0;
                            int sssh = (sssImg != null) ? sssImg.getHeight() : 0;

                            boolean emissive = (sImg != null) && LabPBRConverter.hasEmissiveData(sImg);
                            boolean sssFlag = ((sImg != null) && LabPBRConverter.hasSSSData(sImg)) || (sssImg != null);

                            animatedPbrMap.put(loc, new AnimatedSpriteLabPBR(
                                    normTab, aoTab, heightTab, nw, nh,
                                    rTab, mTab, aTab, sssTab, sw, sh,
                                    sssAlbedoTab, sssw, sssh,
                                    emissive, sssFlag
                            ));
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

        LOGGER.info("Built LabPBR terrain atlas ({} sprites with PBR sidecars, normal={}, height={}, roughness={}, metallic={}, emissive={}, sss={})",
                pbrSpriteCount, hasNormal, hasHeight, hasRoughness, hasMetallic, hasEmissive, hasSSS);

        AtlasAnimationForwarder.initLabPBR(animatedPbrMap);

        return new PbrAtlasResult(normalAtlas, heightAtlas, roughnessAtlas, metallicAtlas, emissiveAtlas,
                sssAtlas, sssAlbedoAtlas, hasNormal, hasHeight, hasRoughness, hasMetallic, hasEmissive, hasSSS);
    }
}
