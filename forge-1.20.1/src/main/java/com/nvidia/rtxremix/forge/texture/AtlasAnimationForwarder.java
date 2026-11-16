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
        SSS(TextureFormat.R8_UNORM, 1),
        SSS_ALBEDO(TextureFormat.RGBA8_UNORM, 4);

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
        flushWithDispatcher(api != null ? api::updateTextureFromMemory : null, blockAtlasHandle, particleAtlasHandle);
    }

    public static void flushWithDispatcher(java.util.function.Consumer<TexturePatchInfo> dispatcher, long blockAtlasHandle) {
        flushWithDispatcher(dispatcher, blockAtlasHandle, 0);
    }

    public static void flushWithDispatcher(java.util.function.Consumer<TexturePatchInfo> dispatcher, long blockAtlasHandle, long particleAtlasHandle) {
        if (!active || dispatcher == null) {
            clear();
            return;
        }

        if ((blockAtlasHandle == 0 || pendingBlockPatches.isEmpty()) &&
            (particleAtlasHandle == 0 || pendingParticlePatches.isEmpty())) {
            clear();
            return;
        }

        List<ByteBuffer> ownedBuffers = new ArrayList<>(pendingBlockPatches.size() * 6);

        try {
            List<TextureBatchEntry> entries = new ArrayList<>(8);

            // Process Block Atlas & PBR Channels
            if (blockAtlasHandle != 0 && !pendingBlockPatches.isEmpty()) {
                int patchCount = pendingBlockPatches.size();

                List<TexturePatch> albedoPatches = new ArrayList<>(patchCount);
                List<TexturePatch> normalPatches = new ArrayList<>(patchCount);
                List<TexturePatch> heightPatches = new ArrayList<>(patchCount);
                List<TexturePatch> roughnessPatches = new ArrayList<>(patchCount);
                List<TexturePatch> metallicPatches = new ArrayList<>(patchCount);
                List<TexturePatch> emissivePatches = new ArrayList<>(patchCount);
                List<TexturePatch> sssPatches = new ArrayList<>(patchCount);
                List<TexturePatch> sssAlbedoPatches = new ArrayList<>(patchCount);

                Long normalHandle = pbrAtlases.get(PbrChannel.NORMAL);
                Long heightHandle = pbrAtlases.get(PbrChannel.HEIGHT);
                Long roughnessHandle = pbrAtlases.get(PbrChannel.ROUGHNESS);
                Long metallicHandle = pbrAtlases.get(PbrChannel.METALLIC);
                Long emissiveHandle = pbrAtlases.get(PbrChannel.EMISSIVE);
                Long sssHandle = pbrAtlases.get(PbrChannel.SSS);
                Long sssAlbedoHandle = pbrAtlases.get(PbrChannel.SSS_ALBEDO);

                for (int i = 0; i < patchCount; i++) {
                    PendingPatch p = pendingBlockPatches.get(i);
                    if (p.width <= 0 || p.height <= 0 || p.pixelPtr == 0) continue;
                    if (p.dstX < 0 || p.dstY < 0) continue;
                    if (p.width > 65535 || p.height > 65535) continue;
                    if (p.rowPitch < p.width * 4) continue;
                    if (p.srcX < 0 || p.srcY < 0) continue;
                    if ((long) p.srcX + (long) p.width > (long) p.srcImageWidth ||
                        (long) p.srcY + (long) p.height > (long) p.srcImageHeight) {
                        continue;
                    }

                    int patchBytesRGBA = p.width * p.height * 4;
                    int patchBytesR8 = p.width * p.height;
                    ByteBuffer srcBuf = MemoryUtil.memByteBuffer(p.pixelPtr, p.rowPitch * p.srcImageHeight);

                    BlockTextureManager.AnimatedSpriteLabPBR pbr =
                            (p.spriteId != null) ? labPbrMap.get(p.spriteId) : null;

                    // 1. Albedo Patch (with AO if available)
                    ByteBuffer albedoBuf = DirectBufferPool.acquire(patchBytesRGBA);
                    ownedBuffers.add(albedoBuf);
                    if (pbr != null && pbr.aoTable != null) {
                        LabPBRConverter.applyAOToPatch(albedoBuf, srcBuf, p.rowPitch,
                                pbr.aoTable, pbr.nW, pbr.nH, p.srcX, p.srcY, p.width, p.height);
                    } else {
                        LabPBRConverter.copyAlbedoPatch(albedoBuf, srcBuf, p.rowPitch, p.srcX, p.srcY, p.width, p.height);
                    }
                    albedoBuf.flip();
                    albedoPatches.add(createPatch(p.dstX, p.dstY, p.width, p.height, albedoBuf, p.width * 4, TextureFormat.RGBA8_UNORM));

                    // 2. Normal Patch (RGBA8)
                    if (normalHandle != null && normalHandle != 0) {
                        ByteBuffer normalBuf = DirectBufferPool.acquire(patchBytesRGBA);
                        ownedBuffers.add(normalBuf);
                        if (pbr != null && pbr.normalTable != null) {
                            LabPBRConverter.copyNormalPatch(normalBuf, pbr.normalTable, pbr.nW, pbr.nH, p.srcX, p.srcY, p.width, p.height);
                        } else {
                            LabPBRConverter.fillFlatNormalPatch(normalBuf, p.width, p.height);
                        }
                        normalBuf.flip();
                        normalPatches.add(createPatch(p.dstX, p.dstY, p.width, p.height, normalBuf, p.width * 4, TextureFormat.RGBA8_UNORM));
                    }

                    // 3. Height Patch (R8)
                    if (heightHandle != null && heightHandle != 0) {
                        ByteBuffer heightBuf = DirectBufferPool.acquire(patchBytesR8);
                        ownedBuffers.add(heightBuf);
                        if (pbr != null && pbr.heightTable != null) {
                            LabPBRConverter.copyR8Patch(heightBuf, pbr.heightTable, pbr.nW, pbr.nH, p.srcX, p.srcY, p.width, p.height);
                        } else {
                            LabPBRConverter.fillR8Patch(heightBuf, (byte) 255, p.width, p.height);
                        }
                        heightBuf.flip();
                        heightPatches.add(createPatch(p.dstX, p.dstY, p.width, p.height, heightBuf, p.width, TextureFormat.R8_UNORM));
                    }

                    // 4. Roughness Patch (R8)
                    if (roughnessHandle != null && roughnessHandle != 0) {
                        ByteBuffer roughnessBuf = DirectBufferPool.acquire(patchBytesR8);
                        ownedBuffers.add(roughnessBuf);
                        if (pbr != null && pbr.sRoughnessTable != null) {
                            LabPBRConverter.copyR8Patch(roughnessBuf, pbr.sRoughnessTable, pbr.sW, pbr.sH, p.srcX, p.srcY, p.width, p.height);
                        } else {
                            LabPBRConverter.fillR8Patch(roughnessBuf, (byte) 204, p.width, p.height);
                        }
                        roughnessBuf.flip();
                        roughnessPatches.add(createPatch(p.dstX, p.dstY, p.width, p.height, roughnessBuf, p.width, TextureFormat.R8_UNORM));
                    }

                    // 5. Metallic Patch (R8)
                    if (metallicHandle != null && metallicHandle != 0) {
                        ByteBuffer metallicBuf = DirectBufferPool.acquire(patchBytesR8);
                        ownedBuffers.add(metallicBuf);
                        if (pbr != null && pbr.sMetallicTable != null) {
                            LabPBRConverter.copyR8Patch(metallicBuf, pbr.sMetallicTable, pbr.sW, pbr.sH, p.srcX, p.srcY, p.width, p.height);
                        } else {
                            LabPBRConverter.fillR8Patch(metallicBuf, (byte) 0, p.width, p.height);
                        }
                        metallicBuf.flip();
                        metallicPatches.add(createPatch(p.dstX, p.dstY, p.width, p.height, metallicBuf, p.width, TextureFormat.R8_UNORM));
                    }

                    // 6. Emissive Patch (RGBA8)
                    if (emissiveHandle != null && emissiveHandle != 0) {
                        ByteBuffer emissiveBuf = DirectBufferPool.acquire(patchBytesRGBA);
                        ownedBuffers.add(emissiveBuf);
                        boolean isLavaOrFire = p.spriteId != null && (p.spriteId.getPath().contains("lava") || p.spriteId.getPath().contains("fire"));
                        if (pbr != null && pbr.hasEmissive && pbr.sAlphaTable != null) {
                            LabPBRConverter.buildEmissivePatch(emissiveBuf, srcBuf, p.rowPitch, pbr.sAlphaTable, pbr.sW, pbr.sH, p.srcX, p.srcY, p.width, p.height);
                        } else if (isLavaOrFire) {
                            LabPBRConverter.copyAlbedoPatch(emissiveBuf, srcBuf, p.rowPitch, p.srcX, p.srcY, p.width, p.height);
                        } else {
                            LabPBRConverter.fillZeroRGBA8(emissiveBuf, p.width, p.height);
                        }
                        emissiveBuf.flip();
                        emissivePatches.add(createPatch(p.dstX, p.dstY, p.width, p.height, emissiveBuf, p.width * 4, TextureFormat.RGBA8_UNORM));
                    }

                    // 7. SSS Thickness Patch (R8, 1 bpp)
                    if (sssHandle != null && sssHandle != 0) {
                        ByteBuffer sssBuf = DirectBufferPool.acquire(patchBytesR8);
                        ownedBuffers.add(sssBuf);
                        if (pbr != null && pbr.hasSSS && pbr.sSssTable != null) {
                            LabPBRConverter.copyR8Patch(sssBuf, pbr.sSssTable, pbr.sW, pbr.sH, p.srcX, p.srcY, p.width, p.height);
                        } else {
                            LabPBRConverter.fillR8Patch(sssBuf, (byte) 191, p.width, p.height);
                        }
                        sssBuf.flip();
                        sssPatches.add(createPatch(p.dstX, p.dstY, p.width, p.height, sssBuf, p.width, TextureFormat.R8_UNORM));
                    }

                    // 8. SSS Albedo Patch (RGBA8)
                    if (sssAlbedoHandle != null && sssAlbedoHandle != 0) {
                        ByteBuffer sssAlbedoBuf = DirectBufferPool.acquire(patchBytesRGBA);
                        ownedBuffers.add(sssAlbedoBuf);
                        if (pbr != null && pbr.sssAlbedoTable != null) {
                            LabPBRConverter.copySssAlbedoPatch(sssAlbedoBuf, pbr.sssAlbedoTable, pbr.sssW, pbr.sssH,
                                    pbr.aoTable, pbr.nW, pbr.nH, p.srcX, p.srcY, p.width, p.height);
                        } else if (pbr != null && pbr.aoTable != null) {
                            LabPBRConverter.applyAOToPatch(sssAlbedoBuf, srcBuf, p.rowPitch,
                                    pbr.aoTable, pbr.nW, pbr.nH, p.srcX, p.srcY, p.width, p.height);
                        } else {
                            LabPBRConverter.copyAlbedoPatch(sssAlbedoBuf, srcBuf, p.rowPitch, p.srcX, p.srcY, p.width, p.height);
                        }
                        sssAlbedoBuf.flip();
                        sssAlbedoPatches.add(createPatch(p.dstX, p.dstY, p.width, p.height, sssAlbedoBuf, p.width * 4, TextureFormat.RGBA8_UNORM));
                    }
                }

                if (!albedoPatches.isEmpty()) {
                    TextureBatchEntry be = new TextureBatchEntry();
                    be.handle = blockAtlasHandle;
                    be.patches = albedoPatches.toArray(new TexturePatch[0]);
                    entries.add(be);
                }
                if (normalHandle != null && normalHandle != 0 && !normalPatches.isEmpty()) {
                    TextureBatchEntry ne = new TextureBatchEntry();
                    ne.handle = normalHandle;
                    ne.patches = normalPatches.toArray(new TexturePatch[0]);
                    entries.add(ne);
                }
                if (heightHandle != null && heightHandle != 0 && !heightPatches.isEmpty()) {
                    TextureBatchEntry he = new TextureBatchEntry();
                    he.handle = heightHandle;
                    he.patches = heightPatches.toArray(new TexturePatch[0]);
                    entries.add(he);
                }
                if (roughnessHandle != null && roughnessHandle != 0 && !roughnessPatches.isEmpty()) {
                    TextureBatchEntry re = new TextureBatchEntry();
                    re.handle = roughnessHandle;
                    re.patches = roughnessPatches.toArray(new TexturePatch[0]);
                    entries.add(re);
                }
                if (metallicHandle != null && metallicHandle != 0 && !metallicPatches.isEmpty()) {
                    TextureBatchEntry me = new TextureBatchEntry();
                    me.handle = metallicHandle;
                    me.patches = metallicPatches.toArray(new TexturePatch[0]);
                    entries.add(me);
                }
                if (emissiveHandle != null && emissiveHandle != 0 && !emissivePatches.isEmpty()) {
                    TextureBatchEntry ee = new TextureBatchEntry();
                    ee.handle = emissiveHandle;
                    ee.patches = emissivePatches.toArray(new TexturePatch[0]);
                    entries.add(ee);
                }
                if (sssHandle != null && sssHandle != 0 && !sssPatches.isEmpty()) {
                    TextureBatchEntry se = new TextureBatchEntry();
                    se.handle = sssHandle;
                    se.patches = sssPatches.toArray(new TexturePatch[0]);
                    entries.add(se);
                }
                if (sssAlbedoHandle != null && sssAlbedoHandle != 0 && !sssAlbedoPatches.isEmpty()) {
                    TextureBatchEntry sae = new TextureBatchEntry();
                    sae.handle = sssAlbedoHandle;
                    sae.patches = sssAlbedoPatches.toArray(new TexturePatch[0]);
                    entries.add(sae);
                }

                // Legacy sidecar atlases backward compatibility
                if (!sidecarPbrAtlases.isEmpty()) {
                    for (int i = 0; i < sidecarPbrAtlases.size(); i++) {
                        long sHandle = sidecarPbrAtlases.get(i);
                        if (sHandle != 0 && sHandle != normalHandle && sHandle != roughnessHandle
                                && sHandle != metallicHandle && sHandle != emissiveHandle && sHandle != heightHandle
                                && sHandle != sssHandle && sHandle != sssAlbedoHandle) {
                            TextureBatchEntry sEntry = buildBatchEntry(sHandle, pendingBlockPatches, new ArrayList<>(pendingBlockPatches.size()), new TextureBatchEntry(), TextureFormat.RGBA8_UNORM);
                            if (sEntry != null) {
                                entries.add(sEntry);
                            }
                        }
                    }
                }
            }

            // Process Particle Atlas
            if (particleAtlasHandle != 0 && !pendingParticlePatches.isEmpty()) {
                TextureBatchEntry pEntry = buildBatchEntry(particleAtlasHandle, pendingParticlePatches, particlePatchesBuffer, particleBatchEntry, TextureFormat.RGBA8_UNORM);
                if (pEntry != null) entries.add(pEntry);
            }

            if (!entries.isEmpty()) {
                TexturePatchInfo info = new TexturePatchInfo();
                info.entries = entries.toArray(new TextureBatchEntry[0]);
                dispatcher.accept(info);
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to forward animated atlas patches: {}", e.getMessage());
        } finally {
            for (ByteBuffer buf : ownedBuffers) {
                DirectBufferPool.release(buf);
            }
            pendingBlockPatches.clear();
            pendingParticlePatches.clear();
        }
    }

    public static TextureBatchEntry buildBatchEntry(long textureHandle, List<PendingPatch> patchesList) {
        return buildBatchEntry(textureHandle, patchesList, TextureFormat.RGBA8_UNORM);
    }

    public static TextureBatchEntry buildBatchEntry(long textureHandle, List<PendingPatch> patchesList, int format) {
        return buildBatchEntry(textureHandle, patchesList, new ArrayList<>(patchesList.size()), new TextureBatchEntry(), format);
    }

    public static TextureBatchEntry buildBatchEntry(long textureHandle,
                                                     List<PendingPatch> patchesList,
                                                     List<TexturePatch> patchBuffer,
                                                     TextureBatchEntry outEntry) {
        return buildBatchEntry(textureHandle, patchesList, patchBuffer, outEntry, TextureFormat.RGBA8_UNORM);
    }

    public static TextureBatchEntry buildBatchEntry(long textureHandle,
                                                     List<PendingPatch> patchesList,
                                                     List<TexturePatch> patchBuffer,
                                                     TextureBatchEntry outEntry,
                                                     int format) {
        if (textureHandle == 0 || patchesList.isEmpty()) return null;
        patchBuffer.clear();

        int bpp = (format == TextureFormat.R8_UNORM) ? 1 : 4;

        for (int i = 0; i < patchesList.size(); i++) {
            PendingPatch p = patchesList.get(i);
            if (p.width <= 0 || p.height <= 0 || p.pixelPtr == 0) continue;
            // Reject invalid destination coordinates
            if (p.dstX < 0 || p.dstY < 0) continue;
            // Enforce upper bound on dimension to prevent uint32 overflow in Remix
            if (p.width > 65535 || p.height > 65535) continue;
            // rowPitch must cover at least one full row of pixel data (bpp bytes per pixel)
            if (p.rowPitch < p.width * bpp) continue;
            // Strict source sub-rectangle bounds check with 64-bit math to prevent integer overflow
            if (p.srcX < 0 || p.srcY < 0) continue;
            if ((long) p.srcX + (long) p.width > (long) p.srcImageWidth ||
                (long) p.srcY + (long) p.height > (long) p.srcImageHeight) {
                continue;
            }

            // Mathematically correct strided offset from base pointer
            long offsetPtr = p.pixelPtr + (long) p.srcY * (long) p.rowPitch + (long) p.srcX * (long) bpp;
            long reqBytes64 = (long) (p.height - 1) * (long) p.rowPitch + (long) p.width * (long) bpp;
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
            patch.format = format;
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
