/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain.capture;

import com.nvidia.rtxremix.forge.util.DirectBufferPool;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pre-categorized interleaved mesh data for a single chunk section.
 * Holds off-heap DirectBufferPool vertex buffers with explicit reference counts.
 */
public final class SectionMeshData {

    public static final int CAT_SOLID = 0;
    public static final int CAT_CUTOUT = 1;
    public static final int CAT_TRANSLUCENT = 2;
    public static final int CAT_OVERLAY = 3;
    public static final int CAT_PARTICLE = 4;
    public static final int CAT_COUNT = 5;

    public record Surface(
        int category,
        String bucketKey,
        ByteBuffer vertexBuffer,
        int vertexCount,
        int indexCount,
        boolean canonicalQuadIndices
    ) {
        public String key() {
            return bucketKey;
        }
    }

    private final List<Surface> surfaces;
    private final int cachedHash;
    private final int[] categoryHashes;
    private final long[] categoryEstimatedMeshBytes;
    private final AtomicInteger references = new AtomicInteger(1);

    public SectionMeshData(List<Surface> surfaces) {
        this(surfaces, computeHash(surfaces));
    }

    SectionMeshData(List<Surface> surfaces, int contentHash) {
        this.surfaces = List.copyOf(surfaces);
        this.cachedHash = contentHash;
        this.categoryHashes = computeCategoryHashes(this.surfaces);
        this.categoryEstimatedMeshBytes = estimateCategoryMeshBytes(this.surfaces);
    }

    public List<Surface> surfaces() {
        if (references.get() <= 0) {
            throw new IllegalStateException("section mesh data read after release");
        }
        return surfaces;
    }

    public boolean isEmpty() { return surfaces.isEmpty(); }

    public void retain() {
        int previous = references.getAndUpdate(count -> count > 0 ? count + 1 : count);
        if (previous <= 0) {
            throw new IllegalStateException("section mesh data retained after release");
        }
    }

    public void release() {
        int remaining = references.decrementAndGet();
        if (remaining > 0) {
            return;
        }
        if (remaining < 0) {
            references.incrementAndGet();
            throw new IllegalStateException("section mesh data released more often than retained");
        }
        for (Surface surface : surfaces) {
            DirectBufferPool.release(surface.vertexBuffer);
        }
    }

    public long categoryEstimatedMeshBytes(int category) {
        validateCategory(category);
        return categoryEstimatedMeshBytes[category];
    }

    public int contentHash() { return cachedHash; }

    public int categoryContentHash(int category) {
        validateCategory(category);
        return categoryHashes[category];
    }

    private static int computeHash(List<Surface> surfaces) {
        int h = surfaces.size();
        for (Surface s : surfaces) {
            h = h * 31 + surfaceHash(s);
        }
        return h;
    }

    private static int[] computeCategoryHashes(List<Surface> surfaces) {
        int[] hashes = new int[CAT_COUNT];
        boolean[] present = new boolean[CAT_COUNT];
        for (Surface surface : surfaces) {
            int category = surface.category;
            if (!isValidCategory(category)) continue;
            if (!present[category]) {
                hashes[category] = 1;
                present[category] = true;
            }
            hashes[category] = hashes[category] * 31 + surfaceHash(surface);
        }
        for (int category = 0; category < hashes.length; category++) {
            if (present[category] && hashes[category] == 0) hashes[category] = 1;
        }
        return hashes;
    }

    private static int surfaceHash(Surface surface) {
        int hash = surface.category;
        hash = hash * 31 + surface.bucketKey.hashCode();
        hash = hash * 31 + surface.vertexCount;
        hash = hash * 31 + surface.indexCount;
        hash = hash * 31 + (surface.canonicalQuadIndices ? 1 : 0);

        ByteBuffer vertices = surface.vertexBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        vertices.position(0).limit(surface.vertexCount * 64);
        while (vertices.remaining() >= Integer.BYTES) hash = hash * 31 + vertices.getInt();

        return hash;
    }

    private static long[] estimateCategoryMeshBytes(List<Surface> surfaces) {
        long[] bytes = new long[CAT_COUNT];
        for (Surface s : surfaces) {
            if (!isValidCategory(s.category)) continue;
            bytes[s.category] += (long) s.vertexCount * 64L;
            bytes[s.category] += (long) s.indexCount * Integer.BYTES;
        }
        return bytes;
    }

    private static boolean isValidCategory(int category) {
        return category >= 0 && category < CAT_COUNT;
    }

    private static void validateCategory(int category) {
        if (!isValidCategory(category)) {
            throw new IllegalArgumentException("Invalid terrain mesh category " + category);
        }
    }
}
