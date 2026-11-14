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
 * Forwards animated block atlas texture patches to the RTX Remix runtime.
 */
public final class AtlasAnimationForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger("rtxremix/AtlasAnimation");

    private static boolean capturing = false;
    private static final List<PendingPatch> pendingPatches = new ArrayList<>();

    public record PendingPatch(
            int dstX, int dstY,
            int srcX, int srcY,
            int width, int height,
            int srcImageWidth, int srcImageHeight,
            long pixelPtr, int rowPitch
    ) {}

    public static void beginAtlasUpload(ResourceLocation location) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(location)) {
            capturing = true;
        }
    }

    public static void endAtlasUpload() {
        capturing = false;
    }

    public static boolean isCapturing() {
        return capturing;
    }

    public static void addPatch(int texX, int texY, int srcX, int srcY,
                                int width, int height,
                                int imgWidth, int imgHeight,
                                long pixelPtr, int rowPitch) {
        if (!capturing || pixelPtr == 0 || width <= 0 || height <= 0) return;
        pendingPatches.add(new PendingPatch(texX, texY, srcX, srcY, width, height, imgWidth, imgHeight, pixelPtr, rowPitch));
    }

    public static void flush(RemixAPI api, long atlasTextureHandle) {
        int count = pendingPatches.size();
        if (count == 0 || atlasTextureHandle == 0 || api == null) return;

        try {
            TexturePatch[] patches = new TexturePatch[count];
            for (int i = 0; i < count; i++) {
                PendingPatch p = pendingPatches.get(i);
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
                patches[i] = patch;
            }

            TextureBatchEntry entry = new TextureBatchEntry();
            entry.handle = atlasTextureHandle;
            entry.patches = patches;

            TexturePatchInfo info = new TexturePatchInfo();
            info.entries = new TextureBatchEntry[]{entry};

            api.updateTextureFromMemory(info);
        } catch (Exception e) {
            LOGGER.debug("Failed to forward animated atlas patches: {}", e.getMessage());
        } finally {
            pendingPatches.clear();
        }
    }

    public static void clear() {
        pendingPatches.clear();
        capturing = false;
    }

    private AtlasAnimationForwarder() {}
}
