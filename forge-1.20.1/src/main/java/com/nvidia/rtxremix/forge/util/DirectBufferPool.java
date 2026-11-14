/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.util;

import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe pool of direct {@link ByteBuffer}s sized in power-of-2 classes.
 * Allocated off-heap via LWJGL's {@link MemoryUtil} to bypass JDK -Xmx and direct-memory budgets.
 */
public final class DirectBufferPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectBufferPool.class);

    private static final int MIN_ORDER = 12;  // 4 KB
    private static final int MAX_ORDER = 26;  // 64 MB
    private static final int BIN_COUNT = MAX_ORDER - MIN_ORDER + 1;

    private static final int PREWARM_PER_BIN = 4;
    private static final long DEFAULT_MAX_POOLED_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_POOLED_BYTES = Math.max(0L,
            Long.getLong("rtxremix.directBufferPool.maxBytes", DEFAULT_MAX_POOLED_BYTES));

    private static final Boolean IN_FLIGHT = Boolean.TRUE;
    private static final Boolean IDLE = Boolean.FALSE;

    @SuppressWarnings("unchecked")
    private static final ConcurrentLinkedQueue<ByteBuffer>[] BINS = new ConcurrentLinkedQueue[BIN_COUNT];
    private static final AtomicLong pooledBytes = new AtomicLong();
    private static final AtomicLong allocatedBytes = new AtomicLong();
    private static final ConcurrentHashMap<Long, Boolean> owned = new ConcurrentHashMap<>();

    static {
        for (int i = 0; i < BIN_COUNT; i++) {
            BINS[i] = new ConcurrentLinkedQueue<>();
        }
        for (int order = 18; order <= 23; order++) {
            ConcurrentLinkedQueue<ByteBuffer> bin = BINS[order - MIN_ORDER];
            int cap = 1 << order;
            for (int j = 0; j < PREWARM_PER_BIN; j++) {
                ByteBuffer buf = MemoryUtil.memAlloc(cap).order(ByteOrder.nativeOrder());
                owned.put(MemoryUtil.memAddress(buf), IDLE);
                bin.offer(buf);
            }
            pooledBytes.addAndGet((long) PREWARM_PER_BIN * cap);
            allocatedBytes.addAndGet((long) PREWARM_PER_BIN * cap);
        }
    }

    private DirectBufferPool() {}

    public static ByteBuffer acquire(int minCapacity) {
        if (minCapacity <= 0) return ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder());

        int order = 32 - Integer.numberOfLeadingZeros(minCapacity - 1);
        order = Math.max(order, MIN_ORDER);

        if (order > MAX_ORDER) {
            allocatedBytes.addAndGet(minCapacity);
            ByteBuffer buf = MemoryUtil.memAlloc(minCapacity).order(ByteOrder.nativeOrder());
            owned.put(MemoryUtil.memAddress(buf), IN_FLIGHT);
            return buf;
        }

        int binIdx = order - MIN_ORDER;
        ByteBuffer buf = BINS[binIdx].poll();
        int cap = 1 << order;

        if (buf != null) {
            pooledBytes.addAndGet(-cap);
            owned.put(MemoryUtil.memAddress(buf), IN_FLIGHT);
            buf.clear();
            return buf;
        }

        allocatedBytes.addAndGet(cap);
        buf = MemoryUtil.memAlloc(cap).order(ByteOrder.nativeOrder());
        owned.put(MemoryUtil.memAddress(buf), IN_FLIGHT);
        return buf;
    }

    public static void release(ByteBuffer buf) {
        if (buf == null || !buf.isDirect() || buf.capacity() == 0) return;

        long addr = MemoryUtil.memAddress(buf);
        Boolean state = owned.put(addr, IDLE);
        if (state == null) {
            return;
        }
        if (state == IDLE) {
            LOGGER.warn("DirectBufferPool: Double release ignored for address 0x{}", Long.toHexString(addr));
            return;
        }

        int cap = buf.capacity();
        int order = 32 - Integer.numberOfLeadingZeros(cap - 1);
        if (order > MAX_ORDER || (1 << order) != cap) {
            allocatedBytes.addAndGet(-cap);
            owned.remove(addr);
            MemoryUtil.memFree(buf);
            return;
        }

        if (pooledBytes.get() + cap > MAX_POOLED_BYTES) {
            allocatedBytes.addAndGet(-cap);
            owned.remove(addr);
            MemoryUtil.memFree(buf);
            return;
        }

        pooledBytes.addAndGet(cap);
        int binIdx = order - MIN_ORDER;
        BINS[binIdx].offer(buf);
    }

    public static long getPooledBytes() {
        return pooledBytes.get();
    }

    public static long getAllocatedBytes() {
        return allocatedBytes.get();
    }
}
