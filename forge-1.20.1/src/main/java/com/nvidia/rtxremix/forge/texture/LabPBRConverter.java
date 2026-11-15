/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;

/**
 * Converts LabPBR _n/_s/_sss textures into Remix-compatible PBR channel data for Forge 1.20.1.
 *
 * <p>LabPBR spec: https://shaderlabs.org/wiki/LabPBR_Material_Standard
 */
public final class LabPBRConverter {

    public static final float DIFFUSION_SSS_RADIUS_SCALE = 100.0f;
    public static final float DIFFUSION_SSS_MAX_SAMPLE_RADIUS = 32.0f;

    private LabPBRConverter() {}

    // --- Per-pixel conversions (int 0-255 domain) ---

    public static int smoothnessToRoughness(int s) {
        double lin = 1.0 - s / 255.0;
        return clamp255((int) (lin * lin * 255.0));
    }

    public static int f0ToMetallic(int g) {
        return g >= 230 ? 255 : 0;
    }

    public static int sssIntensity(int b) {
        return b >= 65 ? clamp255((b - 65) * 255 / 190) : 0;
    }

    // --- Atlas builders (operate on ABGR pixel arrays from NativeImage) ---

    /**
     * Builds an RGBA8 normal atlas from _n images blitted into the given region.
     * Reconstructs Z from X,Y per the LabPBR spec.
     */
    public static void blitNormal(byte[] dst, int atlasW, NativeImage n, int ox, int oy, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = n.getPixelRGBA(x % n.getWidth(), y % n.getHeight());
                int nr = abgr & 0xFF;
                int ng = (abgr >> 8) & 0xFF;
                double nx = (nr / 255.0) * 2.0 - 1.0;
                double ny = (ng / 255.0) * 2.0 - 1.0;
                double nz = Math.sqrt(Math.max(0.0, 1.0 - nx * nx - ny * ny));
                int idx = ((oy + y) * atlasW + (ox + x)) * 4;
                dst[idx]     = (byte) nr;
                dst[idx + 1] = (byte) ng;
                dst[idx + 2] = (byte) clamp255((int) (nz * 255.0));
                dst[idx + 3] = (byte) 255;
            }
        }
    }

    /** Multiplies RGBA albedo in-place by the AO stored in _n blue (0 = occluded, 255 = none). */
    public static void applyAO(byte[] albedoRGBA, int atlasW, NativeImage n, int ox, int oy, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = n.getPixelRGBA(x % n.getWidth(), y % n.getHeight());
                int ao = (abgr >> 16) & 0xFF;
                int idx = ((oy + y) * atlasW + (ox + x)) * 4;
                albedoRGBA[idx]     = (byte) (((albedoRGBA[idx]     & 0xFF) * ao) / 255);
                albedoRGBA[idx + 1] = (byte) (((albedoRGBA[idx + 1] & 0xFF) * ao) / 255);
                albedoRGBA[idx + 2] = (byte) (((albedoRGBA[idx + 2] & 0xFF) * ao) / 255);
            }
        }
    }

    /** Blits a single R8 channel from _n alpha (height). */
    public static void blitHeight(byte[] dst, int atlasW, NativeImage n, int ox, int oy, int w, int h) {
        blitR8(dst, atlasW, n, ox, oy, w, h, 24); // alpha shift
    }

    /** Blits roughness R8 from _s red channel (perceptual smoothness -> linear roughness). */
    public static void blitRoughness(byte[] dst, int atlasW, NativeImage s, int ox, int oy, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = s.getPixelRGBA(x % s.getWidth(), y % s.getHeight());
                dst[(oy + y) * atlasW + (ox + x)] = (byte) smoothnessToRoughness(abgr & 0xFF);
            }
        }
    }

    /** Blits metallic R8 from _s green channel. */
    public static void blitMetallic(byte[] dst, int atlasW, NativeImage s, int ox, int oy, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = s.getPixelRGBA(x % s.getWidth(), y % s.getHeight());
                dst[(oy + y) * atlasW + (ox + x)] = (byte) f0ToMetallic((abgr >> 8) & 0xFF);
            }
        }
    }

    /** Blits SSS intensity as RGBA8 (R=G=B=intensity, A=255) from _s blue channel. */
    public static void blitSSS(byte[] dst, int atlasW, NativeImage s, int ox, int oy, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = s.getPixelRGBA(x % s.getWidth(), y % s.getHeight());
                byte val = (byte) sssIntensity((abgr >> 16) & 0xFF);
                int idx = ((oy + y) * atlasW + (ox + x)) * 4;
                dst[idx]     = val;
                dst[idx + 1] = val;
                dst[idx + 2] = val;
                dst[idx + 3] = (byte) 255;
            }
        }
    }

    /** Blits explicit SSS single-scattering albedo RGB from a _sss texture. */
    public static void blitSingleScatteringAlbedo(byte[] dst, int atlasW, NativeImage sss,
                                                  int ox, int oy, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = sss.getPixelRGBA(x % sss.getWidth(), y % sss.getHeight());
                int idx = ((oy + y) * atlasW + (ox + x)) * 4;
                dst[idx]     = (byte) (abgr & 0xFF);
                dst[idx + 1] = (byte) ((abgr >> 8) & 0xFF);
                dst[idx + 2] = (byte) ((abgr >> 16) & 0xFF);
                dst[idx + 3] = (byte) 255;
            }
        }
    }

    /** Blits emissive RGBA8 from albedo modulated by emission factor. */
    public static void blitEmissive(byte[] dst, int atlasW, byte[] albedoRGBA,
                                    NativeImage s, int ox, int oy, int w, int h,
                                    float fallbackFactor, float gain) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float factor;
                if (s != null) {
                    int alpha = (s.getPixelRGBA(x % s.getWidth(), y % s.getHeight()) >> 24) & 0xFF;
                    factor = (alpha < 255) ? Math.min(1.0f, (alpha / 254.0f) * gain) : fallbackFactor;
                } else {
                    factor = fallbackFactor;
                }
                int ai = ((oy + y) * atlasW + (ox + x)) * 4;
                dst[ai]     = (byte) clamp255((int) ((albedoRGBA[ai]     & 0xFF) * factor));
                dst[ai + 1] = (byte) clamp255((int) ((albedoRGBA[ai + 1] & 0xFF) * factor));
                dst[ai + 2] = (byte) clamp255((int) ((albedoRGBA[ai + 2] & 0xFF) * factor));
                dst[ai + 3] = (byte) (factor > 0 ? 255 : 0);
            }
        }
    }

    // --- Animation and Patch Helpers ---

    public static void applyAOToPatch(byte[] dst, int dstRowPitch,
                                     ByteBuffer src, int srcRowPitch,
                                     byte[] aoTable, int aoW, int aoH,
                                     int aoX, int aoY,
                                     int w, int h) {
        for (int y = 0; y < h; y++) {
            int sRow = y * srcRowPitch;
            int dRow = y * dstRowPitch;
            int aoRow = wrapNonNegative(aoY + y, aoH) * aoW;
            for (int x = 0; x < w; x++) {
                int ao = aoTable[aoRow + wrapNonNegative(aoX + x, aoW)] & 0xFF;
                int si = sRow + x * 4;
                int di = dRow + x * 4;
                dst[di]     = (byte) (((src.get(si)     & 0xFF) * ao) / 255);
                dst[di + 1] = (byte) (((src.get(si + 1) & 0xFF) * ao) / 255);
                dst[di + 2] = (byte) (((src.get(si + 2) & 0xFF) * ao) / 255);
                dst[di + 3] = src.get(si + 3);
            }
        }
    }

    public static void copyAlbedoPatch(byte[] dst, int dstRowPitch,
                                       ByteBuffer src, int srcRowPitch,
                                       int w, int h) {
        int rowBytes = w * 4;
        for (int y = 0; y < h; y++) {
            int sOff = y * srcRowPitch;
            int dOff = y * dstRowPitch;
            for (int i = 0; i < rowBytes; i++) {
                dst[dOff + i] = src.get(sOff + i);
            }
        }
    }

    public static byte[] buildAOTable(NativeImage n) {
        int w = n.getWidth(), h = n.getHeight();
        byte[] table = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                table[y * w + x] = (byte) ((n.getPixelRGBA(x, y) >> 16) & 0xFF);
            }
        }
        return table;
    }

    public static byte[] buildSAlphaTable(NativeImage s) {
        int w = s.getWidth(), h = s.getHeight();
        byte[] table = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                table[y * w + x] = (byte) ((s.getPixelRGBA(x, y) >> 24) & 0xFF);
            }
        }
        return table;
    }

    public static byte[] buildSRoughnessTable(NativeImage s) {
        int w = s.getWidth(), h = s.getHeight();
        byte[] table = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                table[y * w + x] = (byte) smoothnessToRoughness(s.getPixelRGBA(x, y) & 0xFF);
            }
        }
        return table;
    }

    public static byte[] buildSMetallicTable(NativeImage s) {
        int w = s.getWidth(), h = s.getHeight();
        byte[] table = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                table[y * w + x] = (byte) f0ToMetallic((s.getPixelRGBA(x, y) >> 8) & 0xFF);
            }
        }
        return table;
    }

    public static byte[] buildSSssTable(NativeImage s) {
        int w = s.getWidth(), h = s.getHeight();
        byte[] table = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                table[y * w + x] = (byte) sssIntensity((s.getPixelRGBA(x, y) >> 16) & 0xFF);
            }
        }
        return table;
    }

    public static boolean hasSSSData(NativeImage s) {
        for (int y = 0; y < s.getHeight(); y++) {
            for (int x = 0; x < s.getWidth(); x++) {
                if (((s.getPixelRGBA(x, y) >> 16) & 0xFF) >= 65) return true;
            }
        }
        return false;
    }

    public static boolean hasEmissiveData(NativeImage s) {
        for (int y = 0; y < s.getHeight(); y++) {
            for (int x = 0; x < s.getWidth(); x++) {
                if (((s.getPixelRGBA(x, y) >> 24) & 0xFF) < 255) return true;
            }
        }
        return false;
    }

    // --- Resource Loading & VariantCache ---

    public static final class VariantCache {
        private final Map<ResourceLocation, Resource> resources;

        public VariantCache(Map<ResourceLocation, Resource> resources) {
            this.resources = resources;
        }

        public NativeImage loadVariant(ResourceLocation spriteId, String suffix) {
            if (resources == null) return null;
            return loadResource(resources.get(variantId(spriteId, suffix)));
        }

        public int size() {
            return resources != null ? resources.size() : 0;
        }
    }

    public static VariantCache buildVariantCache(ResourceManager rm) {
        if (rm == null) return new VariantCache(null);
        return new VariantCache(rm.listResources("textures", LabPBRConverter::isVariantResource));
    }

    private static boolean isVariantResource(ResourceLocation id) {
        String path = id.getPath();
        return path.endsWith("_n.png") || path.endsWith("_s.png") || path.endsWith("_sss.png");
    }

    private static NativeImage loadResource(Resource resource) {
        if (resource == null) return null;
        try (InputStream stream = resource.open()) {
            return NativeImage.read(stream);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static ResourceLocation variantId(ResourceLocation spriteId, String suffix) {
        return new ResourceLocation(spriteId.getNamespace(), "textures/" + spriteId.getPath() + suffix + ".png");
    }

    // --- Helpers ---

    private static void blitR8(byte[] dst, int atlasW, NativeImage img,
                               int ox, int oy, int w, int h, int shift) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int abgr = img.getPixelRGBA(x % img.getWidth(), y % img.getHeight());
                dst[(oy + y) * atlasW + (ox + x)] = (byte) ((abgr >> shift) & 0xFF);
            }
        }
    }

    public static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static int wrapNonNegative(int value, int size) {
        return (value % size + size) % size;
    }
}
