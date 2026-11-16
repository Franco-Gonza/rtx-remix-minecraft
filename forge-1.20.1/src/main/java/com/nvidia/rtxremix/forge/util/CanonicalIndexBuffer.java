/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.util;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Pre-populated, reusable direct index buffer for canonical quads (0, 2, 1, 0, 3, 2).
 * Eliminates per-mesh direct buffer allocations and per-quad CPU index generation loops.
 */
public final class CanonicalIndexBuffer {

    private static final int INDICES_PER_QUAD = 6;
    private static final int INITIAL_QUADS = 32768; // 196,608 indices (~768 KB)

    private static ByteBuffer sharedBuffer;
    private static int currentQuadCapacity = 0;

    static {
        ensureCapacity(INITIAL_QUADS);
    }

    public static final CanonicalIndexBuffer INSTANCE = new CanonicalIndexBuffer();

    private CanonicalIndexBuffer() {}

    /**
     * Instance accessor for the canonical index buffer.
     */
    public ByteBuffer get(int indexCount) {
        return getBuffer(indexCount);
    }

    /**
     * Ensures the shared buffer has at least {@code requiredQuads} indexed.
     */
    public static synchronized void ensureCapacity(int requiredQuads) {
        if (requiredQuads <= currentQuadCapacity && sharedBuffer != null) {
            return;
        }

        int newCapacity = Math.max(requiredQuads, currentQuadCapacity > 0 ? currentQuadCapacity * 2 : INITIAL_QUADS);
        int totalIndices = newCapacity * INDICES_PER_QUAD;
        int totalBytes = totalIndices * Integer.BYTES;

        ByteBuffer newBuffer = MemoryUtil.memAlloc(totalBytes).order(ByteOrder.nativeOrder());

        // Fill canonical quad pattern (0, 2, 1, 0, 3, 2)
        for (int q = 0; q < newCapacity; q++) {
            int base = q * 4;
            newBuffer.putInt(base);
            newBuffer.putInt(base + 2);
            newBuffer.putInt(base + 1);
            newBuffer.putInt(base);
            newBuffer.putInt(base + 3);
            newBuffer.putInt(base + 2);
        }
        newBuffer.flip();

        if (sharedBuffer != null) {
            MemoryUtil.memFree(sharedBuffer);
        }
        sharedBuffer = newBuffer;
        currentQuadCapacity = newCapacity;
    }

    /**
     * Returns a sliced view of the canonical index buffer for the given index count.
     * Slices share the same underlying off-heap memory with zero allocations and zero copies.
     */
    public static ByteBuffer getBuffer(int indexCount) {
        if (indexCount <= 0) {
            if (sharedBuffer != null) {
                ByteBuffer dup = sharedBuffer.duplicate().order(ByteOrder.nativeOrder());
                dup.position(0);
                dup.limit(0);
                return dup;
            }
            return ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());
        }

        int quads = (indexCount + INDICES_PER_QUAD - 1) / INDICES_PER_QUAD;
        if (quads > currentQuadCapacity) {
            ensureCapacity(quads);
        }

        ByteBuffer dup = sharedBuffer.duplicate().order(ByteOrder.nativeOrder());
        dup.position(0);
        dup.limit(indexCount * Integer.BYTES);
        return dup;
    }

    /**
     * Releases the off-heap memory on shutdown.
     */
    public static synchronized void free() {
        if (sharedBuffer != null) {
            MemoryUtil.memFree(sharedBuffer);
            sharedBuffer = null;
            currentQuadCapacity = 0;
        }
    }
}
