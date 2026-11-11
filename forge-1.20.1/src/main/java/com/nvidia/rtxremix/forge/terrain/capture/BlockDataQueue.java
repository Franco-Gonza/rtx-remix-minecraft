/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain.capture;

import net.minecraft.core.SectionPos;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe queue that transfers block-gen section data from chunk-build
 * worker threads to the render thread.
 */
public final class BlockDataQueue {

    private static final int DEFAULT_MAX_PENDING_ITEMS = 512;
    private static final long DEFAULT_MAX_PENDING_BYTES = 128L * 1024L * 1024L;
    private static final int DEFAULT_MAX_DRAIN_ITEMS = 32;
    private static final long DEFAULT_MAX_DRAIN_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_PENDING_ITEMS = Math.max(16,
            Integer.getInteger("rtxremix.blockDataQueue.maxPendingItems", DEFAULT_MAX_PENDING_ITEMS));
    private static final long MAX_PENDING_BYTES = Math.max(16L * 1024L * 1024L,
            Long.getLong("rtxremix.blockDataQueue.maxPendingBytes", DEFAULT_MAX_PENDING_BYTES));
    private static final int MAX_DRAIN_ITEMS = Math.max(1,
            Integer.getInteger("rtxremix.blockDataQueue.maxDrainItems", DEFAULT_MAX_DRAIN_ITEMS));
    private static final long MAX_DRAIN_BYTES = Math.max(4L * 1024L * 1024L,
            Long.getLong("rtxremix.blockDataQueue.maxDrainBytes", DEFAULT_MAX_DRAIN_BYTES));
    private static final long DEFAULT_PUBLISH_WAIT_BUDGET_NS = Math.max(50_000_000L,
            Long.getLong("rtxremix.blockDataQueue.publishWaitBudgetNs", 500_000_000L));
    private static volatile long publishWaitBudgetNs = DEFAULT_PUBLISH_WAIT_BUDGET_NS;
    private static final long EVICTION_RETRY_WAIT_NS = 50_000_000L;

    private static final AtomicLong BACKPRESSURE_WAITS = new AtomicLong();
    private static final AtomicLong BACKPRESSURE_EVICTIONS = new AtomicLong();
    private static final AtomicLong NEXT_CHUNK_TOKEN = new AtomicLong();
    private static final AtomicLong NEXT_QUEUE_GENERATION = new AtomicLong();
    private static final AtomicReference<QueueState> STATE =
            new AtomicReference<>(new QueueState(allocateQueueGeneration()));

    public record CaptureToken(long queueGeneration, long chunkToken) {}
    public record QueuedCapture(SectionBlockData data, CaptureToken capture) {}
    public record EvictedSection(int x, int y, int z) {}
    private record QueuedSectionData(QueuedCapture capture, long bytes) {}

    private static final class QueueState {
        final long generation;
        final ConcurrentLinkedQueue<QueuedSectionData> queue = new ConcurrentLinkedQueue<>();
        final ConcurrentHashMap<Long, Long> chunkTokens = new ConcurrentHashMap<>();
        final AtomicInteger reservedItems = new AtomicInteger();
        final AtomicLong reservedBytes = new AtomicLong();
        final AtomicInteger pendingItems = new AtomicInteger();
        final AtomicLong pendingBytes = new AtomicLong();
        final AtomicInteger activePublishers = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        final ReentrantLock capacityLock = new ReentrantLock();
        final Condition capacityChanged = capacityLock.newCondition();
        final AtomicInteger capacityWaiters = new AtomicInteger();
        final ConcurrentLinkedQueue<EvictedSection> evictedSections =
                new ConcurrentLinkedQueue<>();

        QueueState(long generation) {
            this.generation = generation;
        }

        void close() {
            if (!closed.compareAndSet(false, true)) return;
            signalCapacityWaiters();
            discardRetiredDataIfQuiescent();
        }

        void signalCapacityWaiters() {
            if (capacityWaiters.get() == 0) return;
            capacityLock.lock();
            try {
                capacityChanged.signalAll();
            } finally {
                capacityLock.unlock();
            }
        }

        void discardRetiredDataIfQuiescent() {
            if (closed.get() && activePublishers.get() == 0) {
                QueuedSectionData retired;
                while ((retired = queue.poll()) != null) {
                    releaseMeshData(retired.capture.data());
                }
            }
        }
    }

    private BlockDataQueue() {}

    public static CaptureToken beginCapture(int cx, int cz) {
        QueueState state = STATE.get();
        long key = packChunkKey(cx, cz);
        long chunkToken = state.chunkTokens.computeIfAbsent(
                key, ignored -> allocateChunkToken());
        return new CaptureToken(state.generation, chunkToken);
    }

    public static void markChunkUnloaded(int cx, int cz) {
        QueueState state = STATE.get();
        state.chunkTokens.remove(packChunkKey(cx, cz));
        state.signalCapacityWaiters();
    }

    public static void enqueue(SectionBlockData data, CaptureToken capture) {
        QueueState state = STATE.get();
        if (state.generation != capture.queueGeneration()
                || !isCurrentChunkToken(state, data, capture.chunkToken())) {
            releaseMeshData(data);
            return;
        }

        long bytes = estimateBytes(data);
        if (!acquireCapacity(state, data, capture, bytes)) {
            releaseMeshData(data);
            return;
        }

        if (!beginPublish(state)) {
            releaseCapacity(state, bytes);
            state.signalCapacityWaiters();
            releaseMeshData(data);
            return;
        }
        try {
            if (!isCaptureCurrent(state, data, capture)) {
                releaseCapacity(state, bytes);
                state.signalCapacityWaiters();
                releaseMeshData(data);
                return;
            }

            state.pendingItems.incrementAndGet();
            state.pendingBytes.addAndGet(bytes);
            state.queue.add(new QueuedSectionData(new QueuedCapture(data, capture), bytes));
        } finally {
            endPublish(state);
        }
    }

    public static int drain(List<QueuedCapture> out) {
        QueueState state = STATE.get();
        int removedCount = 0;
        int outputCount = 0;
        long bytes = 0;
        while (true) {
            QueuedSectionData next = state.queue.peek();
            if (next == null) {
                break;
            }
            if (removedCount >= MAX_DRAIN_ITEMS
                    || (removedCount > 0 && (bytes >= MAX_DRAIN_BYTES
                    || next.bytes > MAX_DRAIN_BYTES - bytes))) {
                break;
            }

            next = state.queue.poll();
            if (next == null) continue;

            state.pendingItems.decrementAndGet();
            state.pendingBytes.addAndGet(-next.bytes);
            releaseCapacity(state, next.bytes);

            if (isCurrent(next.capture)) {
                out.add(next.capture);
                outputCount++;
            } else {
                releaseMeshData(next.capture.data());
            }
            removedCount++;
            bytes += next.bytes;
        }
        if (removedCount > 0) state.signalCapacityWaiters();
        return outputCount;
    }

    public static void clear() {
        QueueState retired = STATE.getAndSet(new QueueState(allocateQueueGeneration()));
        retired.close();
    }

    public static int pendingCount() {
        return STATE.get().pendingItems.get();
    }

    public static long pendingBytes() {
        return STATE.get().pendingBytes.get();
    }

    public static long drainBackpressureWaits() {
        return BACKPRESSURE_WAITS.getAndSet(0);
    }

    public static long drainBackpressureEvictions() {
        return BACKPRESSURE_EVICTIONS.getAndSet(0);
    }

    public static int drainEvictedSections(List<EvictedSection> out, int maxItems) {
        QueueState state = STATE.get();
        int drained = 0;
        EvictedSection section;
        while (drained < maxItems && (section = state.evictedSections.poll()) != null) {
            out.add(section);
            drained++;
        }
        return drained;
    }

    public static boolean isCurrent(QueuedCapture queued) {
        QueueState state = STATE.get();
        CaptureToken capture = queued.capture();
        return state.generation == capture.queueGeneration()
                && !state.closed.get()
                && isCurrentChunkToken(state, queued.data(), capture.chunkToken());
    }

    private static boolean acquireCapacity(QueueState state, SectionBlockData data,
                                           CaptureToken capture, long bytes) {
        if (!isCaptureCurrent(state, data, capture)) return false;
        if (tryReserveCapacity(state, bytes)) return true;

        if (isRenderThread()) {
            state.reservedItems.incrementAndGet();
            state.reservedBytes.addAndGet(bytes);
            return true;
        }

        long waitBudgetNs = publishWaitBudgetNs;
        BACKPRESSURE_WAITS.incrementAndGet();
        try {
            state.capacityLock.lockInterruptibly();
            state.capacityWaiters.incrementAndGet();
            try {
                while (isCaptureCurrent(state, data, capture)) {
                    if (tryReserveCapacity(state, bytes)) return true;
                    if (waitBudgetNs > 0L) {
                        waitBudgetNs = state.capacityChanged.awaitNanos(waitBudgetNs);
                        continue;
                    }
                    if (evictIntoReservation(state, data, bytes)) return true;
                    waitBudgetNs = EVICTION_RETRY_WAIT_NS;
                }
                return false;
            } finally {
                state.capacityWaiters.decrementAndGet();
                state.capacityLock.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean evictIntoReservation(QueueState state, SectionBlockData incoming,
                                                long bytes) {
        long held = evictQueueHead(state, incoming);
        if (held < 0L) return false;

        int foldedItems = 0;
        while (!tryReserveBytes(state, held, bytes)) {
            long next = evictQueueHead(state, incoming);
            if (next < 0L) {
                releaseCapacity(state, held);
                state.signalCapacityWaiters();
                return false;
            }
            state.reservedItems.decrementAndGet();
            foldedItems++;
            held += next;
        }
        if (foldedItems > 0 || held > bytes) state.signalCapacityWaiters();
        return true;
    }

    private static long evictQueueHead(QueueState state, SectionBlockData incoming) {
        QueuedSectionData head = state.queue.poll();
        if (head == null) return -1L;

        state.pendingItems.decrementAndGet();
        state.pendingBytes.addAndGet(-head.bytes);
        releaseMeshData(head.capture.data());

        SectionPos sacrificed = head.capture.data().sectionPos();
        if (isCurrent(head.capture) && !sacrificed.equals(incoming.sectionPos())) {
            BACKPRESSURE_EVICTIONS.incrementAndGet();
            state.evictedSections.add(new EvictedSection(
                    sacrificed.getX(), sacrificed.getY(), sacrificed.getZ()));
        }
        return head.bytes;
    }

    private static boolean tryReserveCapacity(QueueState state, long bytes) {
        if (state.closed.get() || !tryReserveItem(state)) return false;
        if (tryReserveBytes(state, 0L, bytes)) return true;

        state.reservedItems.decrementAndGet();
        state.signalCapacityWaiters();
        return false;
    }

    private static boolean tryReserveItem(QueueState state) {
        while (true) {
            int items = state.reservedItems.get();
            if (items >= MAX_PENDING_ITEMS) return false;
            if (state.reservedItems.compareAndSet(items, items + 1)) return true;
        }
    }

    private static boolean tryReserveBytes(QueueState state, long held, long wanted) {
        while (true) {
            long reserved = state.reservedBytes.get();
            long others = reserved - held;
            if (wanted > held && others > 0 && wanted > MAX_PENDING_BYTES - others) return false;
            if (state.reservedBytes.compareAndSet(reserved, reserved + wanted - held)) return true;
        }
    }

    private static void releaseCapacity(QueueState state, long bytes) {
        state.reservedBytes.addAndGet(-bytes);
        state.reservedItems.decrementAndGet();
    }

    private static boolean beginPublish(QueueState state) {
        state.activePublishers.incrementAndGet();
        if (!state.closed.get() && STATE.get() == state) return true;
        endPublish(state);
        return false;
    }

    private static void endPublish(QueueState state) {
        state.activePublishers.decrementAndGet();
        state.discardRetiredDataIfQuiescent();
    }

    private static boolean isCaptureCurrent(QueueState state, SectionBlockData data,
                                            CaptureToken capture) {
        return STATE.get() == state
                && !state.closed.get()
                && state.generation == capture.queueGeneration()
                && isCurrentChunkToken(state, data, capture.chunkToken());
    }

    private static boolean isRenderThread() {
        return Thread.currentThread().getName().equals("Render thread");
    }

    private static long estimateBytes(SectionBlockData data) {
        long bytes = 128;
        if (data.opacityBitmap() != null) {
            bytes += (long) data.opacityBitmap().length * Long.BYTES;
        }
        if (data.lights() != null) {
            bytes += (long) data.lights().size() * 128L;
        }
        if (data.meshData() != null) {
            for (SectionMeshData.Surface surface : data.meshData().surfaces()) {
                bytes += 64;
                bytes += surface.vertexBuffer().capacity();
            }
        }
        return Math.max(bytes, 1L);
    }

    private static void releaseMeshData(SectionBlockData data) {
        SectionMeshData mesh = data.meshData();
        if (mesh != null) {
            mesh.release();
        }
    }

    private static boolean isCurrentChunkToken(QueueState state, SectionBlockData data,
                                               long captureChunkToken) {
        Long current = state.chunkTokens.get(packChunkKey(
                data.sectionPos().getX(), data.sectionPos().getZ()));
        return current != null && current == captureChunkToken;
    }

    private static long allocateChunkToken() {
        long token = NEXT_CHUNK_TOKEN.incrementAndGet();
        if (token == 0) {
            throw new IllegalStateException("Chunk capture token space exhausted");
        }
        return token;
    }

    private static long allocateQueueGeneration() {
        long generation = NEXT_QUEUE_GENERATION.incrementAndGet();
        if (generation == 0) {
            throw new IllegalStateException("Block-data queue generation space exhausted");
        }
        return generation;
    }

    private static long packChunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
