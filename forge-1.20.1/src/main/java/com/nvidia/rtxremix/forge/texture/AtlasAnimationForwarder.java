/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.texture;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.forge.util.DirectBufferPool;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Forwards animated block and particle atlas texture patches to the RTX Remix runtime.
 */
public final class AtlasAnimationForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger("rtxremix/AtlasAnimation");

    public enum PbrChannel {
        ALBEDO(TextureFormat.RGBA8_UNORM, 4),
        NORMAL(TextureFormat.RGBA8_UNORM, 4),
        HEIGHT(TextureFormat.R8_UNORM, 1),
        ROUGHNESS(TextureFormat.R8_UNORM, 1),
        METALLIC(TextureFormat.R8_UNORM, 1),
        EMISSIVE(TextureFormat.RGBA8_UNORM, 4),
        SSS(TextureFormat.RGBA8_UNORM, 4);

        public final int format;
        public final int bytesPerPixel;

        PbrChannel(int format, int bytesPerPixel) {
            this.format = format;
            this.bytesPerPixel = bytesPerPixel;
        }
    }

    private static volatile boolean active = true;
    private static final int MAX_PENDING_PATCHES = 512;

    private static final ThreadLocal<ResourceLocation> currentAtlas = new ThreadLocal<>();
    private static final List<PendingPatch> pendingBlockPatches = new ArrayList<>(64);
    private static final List<PendingPatch> pendingParticlePatches = new ArrayList<>(16);

    // Reusable buffers to minimize heap allocations on hot render path
    private static final List<TexturePatch> blockPatchesBuffer = new ArrayList<>(64);
    private static final List<TexturePatch> particlePatchesBuffer = new ArrayList<>(16);
    private static final TextureBatchEntry blockBatchEntry = new TextureBatchEntry();
    private static final TextureBatchEntry particleBatchEntry = new TextureBatchEntry();
    private static final TexturePatchInfo sharedPatchInfo = new TexturePatchInfo();

    private static final Map<PbrChannel, Long> pbrAtlases = new EnumMap<>(PbrChannel.class);
    private static final List<Long> sidecarPbrAtlases = new ArrayList<>(4);
    private static Map<ResourceLocation, BlockTextureManager.AnimatedSpriteLabPBR> labPbrMap = Collections.emptyMap();

    public record PendingPatch(
            ResourceLocation spriteId,
            int dstX, int dstY,
            int srcX, int srcY,
            int width, int height,
            int srcImageWidth, int srcImageHeight,
            long pixelPtr, int rowPitch
    ) {
        public PendingPatch(int dstX, int dstY, int srcX, int srcY, int width, int height,
                            int srcImageWidth, int srcImageHeight, long pixelPtr, int rowPitch) {
            this(null, dstX, dstY, srcX, srcY, width, height, srcImageWidth, srcImageHeight, pixelPtr, rowPitch);
        }
    }

    public static void initLabPBR(Map<ResourceLocation, BlockTextureManager.AnimatedSpriteLabPBR> map) {
        labPbrMap = map != null ? map : Collections.emptyMap();
    }

    public static void registerPbrAtlas(long handle, PbrChannel channel) {
        if (handle != 0 && channel != null) {
            pbrAtlases.put(channel, handle);
        }
    }

    public static Long getPbrAtlas(PbrChannel channel) {
        return pbrAtlases.get(channel);
    }

    public static void registerSidecarAtlas(long handle) {
        if (handle != 0 && !sidecarPbrAtlases.contains(handle)) {
            sidecarPbrAtlases.add(handle);
        }
    }

    public static void clearSidecarAtlases() {
        pbrAtlases.clear();
        sidecarPbrAtlases.clear();
    }

    public static TexturePatch createPatch(int dstX, int dstY, int width, int height,
                                           ByteBuffer pixelData, int rowPitch, int format) {
        TexturePatch patch = new TexturePatch();
        patch.dstX = dstX;
        patch.dstY = dstY;
        patch.width = width;
        patch.height = height;
        patch.pixelData = pixelData;
        patch.rowPitch = rowPitch;
        patch.format = format;
        return patch;
    }

    public static void setActive(boolean isRunning) {
        active = isRunning;
        if (!isRunning) {
            clear();
        }
    }

    public static void pause() {
        setActive(false);
    }

    public static void resume() {
        setActive(true);
    }

    public static void beginAtlasUpload(ResourceLocation location) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(location) || TextureAtlas.LOCATION_PARTICLES.equals(location)) {
            currentAtlas.set(location);
        } else {
            currentAtlas.remove();
        }
    }

    public static void endAtlasUpload() {
        currentAtlas.remove();
    }

    public static boolean isCapturing() {
        return active && currentAtlas.get() != null;
    }

    public static void addPatch(int texX, int texY, int srcX, int srcY,
                                int width, int height,
                                int imgWidth, int imgHeight,
                                long pixelPtr, int rowPitch) {
        addPatch(null, texX, texY, srcX, srcY, width, height, imgWidth, imgHeight, pixelPtr, rowPitch);
    }

    public static void addPatch(ResourceLocation spriteId,
                                int texX, int texY, int srcX, int srcY,
                                int width, int height,
                                int imgWidth, int imgHeight,
                                long pixelPtr, int rowPitch) {
        if (!active || pixelPtr == 0 || width <= 0 || height <= 0) return;
        ResourceLocation atlas = currentAtlas.get();
        if (atlas == null) return;

        PendingPatch patch = new PendingPatch(spriteId, texX, texY, srcX, srcY, width, height, imgWidth, imgHeight, pixelPtr, rowPitch);
        if (TextureAtlas.LOCATION_BLOCKS.equals(atlas)) {
            if (pendingBlockPatches.size() >= MAX_PENDING_PATCHES) {
                pendingBlockPatches.clear();
            }
            pendingBlockPatches.add(patch);
        } else if (TextureAtlas.LOCATION_PARTICLES.equals(atlas)) {
            if (pendingParticlePatches.size() >= MAX_PENDING_PATCHES) {
                pendingParticlePatches.clear();
            }
            pendingParticlePatches.add(patch);
        }
    }

    public static void flush(RemixAPI api, long blockAtlasHandle) {
        flush(api, blockAtlasHandle, 0);
    }

    public static void flush(RemixAPI api, long blockAtlasHandle, long particleAtlasHandle) {
        if (!active || api == null) {
            clear();
            return;
        }

        int entryCount = 0;
        TextureBatchEntry bEntry = null;
        TextureBatchEntry pEntry = null;
        List<TextureBatchEntry> sidecarEntries = null;

        if (blockAtlasHandle != 0 && !pendingBlockPatches.isEmpty()) {
            bEntry = buildBatchEntry(blockAtlasHandle, pendingBlockPatches, blockPatchesBuffer, blockBatchEntry);
            if (bEntry != null) {
                entryCount++;
                if (!sidecarPbrAtlases.isEmpty()) {
                    sidecarEntries = new ArrayList<>(sidecarPbrAtlases.size());
                    for (int i = 0; i < sidecarPbrAtlases.size(); i++) {
                        long sHandle = sidecarPbrAtlases.get(i);
                        if (sHandle != 0) {
                            TextureBatchEntry sEntry = buildBatchEntry(sHandle, pendingBlockPatches, new ArrayList<>(pendingBlockPatches.size()), new TextureBatchEntry());
                            if (sEntry != null) {
                                sidecarEntries.add(sEntry);
                                entryCount++;
                            }
                        }
                    }
                }
            }
        }
        if (particleAtlasHandle != 0 && !pendingParticlePatches.isEmpty()) {
            pEntry = buildBatchEntry(particleAtlasHandle, pendingParticlePatches, particlePatchesBuffer, particleBatchEntry);
            if (pEntry != null) entryCount++;
        }

        if (entryCount > 0) {
            try {
                TextureBatchEntry[] batchEntries = new TextureBatchEntry[entryCount];
                int idx = 0;
                if (bEntry != null) batchEntries[idx++] = bEntry;
                if (sidecarEntries != null) {
                    for (int i = 0; i < sidecarEntries.size(); i++) {
                        batchEntries[idx++] = sidecarEntries.get(i);
                    }
                }
                if (pEntry != null) batchEntries[idx] = pEntry;

                sharedPatchInfo.entries = batchEntries;
                api.updateTextureFromMemory(sharedPatchInfo);
            } catch (Exception e) {
                LOGGER.debug("Failed to forward animated atlas patches: {}", e.getMessage());
            }
        }

        pendingBlockPatches.clear();
        pendingParticlePatches.clear();
    }

    public static TextureBatchEntry buildBatchEntry(long textureHandle, List<PendingPatch> patchesList) {
        return buildBatchEntry(textureHandle, patchesList, new ArrayList<>(patchesList.size()), new TextureBatchEntry());
    }

    private static TextureBatchEntry buildBatchEntry(long textureHandle,
                                                     List<PendingPatch> patchesList,
                                                     List<TexturePatch> patchBuffer,
                                                     TextureBatchEntry outEntry) {
        if (textureHandle == 0 || patchesList.isEmpty()) return null;
        patchBuffer.clear();

        for (int i = 0; i < patchesList.size(); i++) {
            PendingPatch p = patchesList.get(i);
            if (p.width <= 0 || p.height <= 0 || p.pixelPtr == 0) continue;
            // Reject invalid destination coordinates
            if (p.dstX < 0 || p.dstY < 0) continue;
            // Enforce upper bound on dimension to prevent uint32 overflow in Remix
            if (p.width > 65535 || p.height > 65535) continue;
            // rowPitch must cover at least one full row of pixel data (4 bpp for RGBA8)
            if (p.rowPitch < p.width * 4) continue;
            // Strict source sub-rectangle bounds check with 64-bit math to prevent integer overflow
            if (p.srcX < 0 || p.srcY < 0) continue;
            if ((long) p.srcX + (long) p.width > (long) p.srcImageWidth ||
                (long) p.srcY + (long) p.height > (long) p.srcImageHeight) {
                continue;
            }

            // Mathematically correct strided offset from base pointer
            long offsetPtr = p.pixelPtr + (long) p.srcY * (long) p.rowPitch + (long) p.srcX * 4L;
            long reqBytes64 = (long) (p.height - 1) * (long) p.rowPitch + (long) p.width * 4L;
            if (reqBytes64 <= 0 || reqBytes64 > Integer.MAX_VALUE) continue;
            int requiredBytes = (int) reqBytes64;

            ByteBuffer buf = MemoryUtil.memByteBuffer(offsetPtr, requiredBytes);

            TexturePatch patch = new TexturePatch();
            patch.dstX = p.dstX;
            patch.dstY = p.dstY;
            patch.width = p.width;
            patch.height = p.height;
            patch.pixelData = buf;
            patch.rowPitch = p.rowPitch;
            patch.format = TextureFormat.RGBA8_UNORM;
            patchBuffer.add(patch);
        }

        if (patchBuffer.isEmpty()) return null;

        outEntry.handle = textureHandle;
        outEntry.patches = patchBuffer.toArray(new TexturePatch[0]);
        return outEntry;
    }

    public static void clear() {
        pendingBlockPatches.clear();
        pendingParticlePatches.clear();
        blockPatchesBuffer.clear();
        particlePatchesBuffer.clear();
        currentAtlas.remove();
    }

    private AtlasAnimationForwarder() {}
}
