/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.profiling;

/**
 * Profiling scope for Tracy and performance measurement during chunk compiling.
 */
public final class ProfileScope implements AutoCloseable {
    private static final ProfileScope NOOP = new ProfileScope();

    private ProfileScope() {}

    public static ProfileScope begin(String name) {
        return NOOP;
    }

    public ProfileScope surfaceCount(int count) {
        return this;
    }

    public ProfileScope vertexCount(int count) {
        return this;
    }

    public ProfileScope bytes(long bytes) {
        return this;
    }

    public ProfileScope blockCount(int count) {
        return this;
    }

    public ProfileScope fluidCount(int count) {
        return this;
    }

    @Override
    public void close() {}
}
