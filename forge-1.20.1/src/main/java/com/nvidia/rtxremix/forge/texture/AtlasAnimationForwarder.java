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
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Forwards animated block and particle atlas texture patches to the RTX Remix runtime.
 */
public final class AtlasAnimationForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger("rtxremix/AtlasAnimation");

    private static ResourceLocation currentAtlas = null;
    private static final List<PendingPatch> pendingBlockPatches = new ArrayList<>();
    private static final List<PendingPatch> pendingParticlePatches = new ArrayList<>();

    public record PendingPatch(
            int dstX, int dstY,
            int srcX, int srcY,
            int width, int height,
            int srcImageWidth, int srcImageHeight,
            long pixelPtr, int rowPitch
    ) {}

    public static void beginAtlasUpload(ResourceLocation location) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(location) || TextureAtlas.LOCATION_PARTICLES.equals(location)) {
            currentAtlas = location;
        } else {
            currentAtlas = null;
        }
    }

    public static void endAtlasUpload() {
        currentAtlas = null;
    }

    public static boolean isCapturing() {
        return currentAtlas != null;
    }

    public static void addPatch(int texX, int texY, int srcX, int srcY,
                                int width, int height,
                                int imgWidth, int imgHeight,
                                long pixelPtr, int rowPitch) {
        if (currentAtlas == null || pixelPtr == 0 || width <= 0 || height <= 0) return;
        PendingPatch patch = new PendingPatch(texX, texY, srcX, srcY, width, height, imgWidth, imgHeight, pixelPtr, rowPitch);
        if (TextureAtlas.LOCATION_BLOCKS.equals(currentAtlas)) {
            pendingBlockPatches.add(patch);
        } else if (TextureAtlas.LOCATION_PARTICLES.equals(currentAtlas)) {
            pendingParticlePatches.add(patch);
        }
    }

    public static void flush(RemixAPI api, long blockAtlasHandle) {
        flush(api, blockAtlasHandle, 0);
    }

    public static void flush(RemixAPI api, long blockAtlasHandle, long particleAtlasHandle) {
        if (api == null) {
            clear();
            return;
        }

        List<TextureBatchEntry> entries = new ArrayList<>(2);
        if (blockAtlasHandle != 0 && !pendingBlockPatches.isEmpty()) {
            TextureBatchEntry entry = buildBatchEntry(blockAtlasHandle, pendingBlockPatches);
            if (entry != null) entries.add(entry);
        }
        if (particleAtlasHandle != 0 && !pendingParticlePatches.isEmpty()) {
            TextureBatchEntry entry = buildBatchEntry(particleAtlasHandle, pendingParticlePatches);
            if (entry != null) entries.add(entry);
        }

        if (!entries.isEmpty()) {
            try {
                TexturePatchInfo info = new TexturePatchInfo();
                info.entries = entries.toArray(new TextureBatchEntry[0]);
                api.updateTextureFromMemory(info);
            } catch (Exception e) {
                LOGGER.debug("Failed to forward animated atlas patches: {}", e.getMessage());
            }
        }

        pendingBlockPatches.clear();
        pendingParticlePatches.clear();
    }

    private static TextureBatchEntry buildBatchEntry(long textureHandle, List<PendingPatch> patchesList) {
        if (patchesList.isEmpty()) return null;
        List<TexturePatch> validPatches = new ArrayList<>(patchesList.size());
        for (PendingPatch p : patchesList) {
            if (p.width <= 0 || p.height <= 0 || p.pixelPtr == 0) continue;
            // Strict bounds check: ensure the sub-rectangle fits inside the source image
            if (p.srcX < 0 || p.srcY < 0 || p.srcX + p.width > p.srcImageWidth || p.srcY + p.height > p.srcImageHeight) {
                continue;
            }
            long offsetPtr = p.pixelPtr + ((long) p.srcY * p.srcImageWidth + p.srcX) * 4L;
            int requiredBytes = (p.height - 1) * p.rowPitch + p.width * 4;
            ByteBuffer buf = MemoryUtil.memByteBuffer(offsetPtr, requiredBytes);

            TexturePatch patch = new TexturePatch();
            patch.dstX = p.dstX;
            patch.dstY = p.dstY;
            patch.width = p.width;
            patch.height = p.height;
            patch.pixelData = buf;
            patch.rowPitch = p.rowPitch;
            patch.format = TextureFormat.RGBA8_UNORM;
            validPatches.add(patch);
        }

        if (validPatches.isEmpty()) return null;

        TextureBatchEntry entry = new TextureBatchEntry();
        entry.handle = textureHandle;
        entry.patches = validPatches.toArray(new TexturePatch[0]);
        return entry;
    }

    public static void clear() {
        pendingBlockPatches.clear();
        pendingParticlePatches.clear();
        currentAtlas = null;
    }

    private AtlasAnimationForwarder() {}
}
