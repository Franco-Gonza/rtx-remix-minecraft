/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.terrain;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Counts section builds running with RTX capture off, the only worker-thread readers of client light.
 */
public final class VanillaSectionBuilds {
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private VanillaSectionBuilds() {}

    public static boolean beginBuild(BooleanSupplier captureGate) {
        IN_FLIGHT.incrementAndGet();
        boolean capturing = captureGate.getAsBoolean();
        if (capturing) {
            IN_FLIGHT.decrementAndGet();
        }
        return capturing;
    }

    public static void endBuild(boolean capturing) {
        if (!capturing) {
            IN_FLIGHT.decrementAndGet();
        }
    }

    public static boolean anyInFlight() {
        return IN_FLIGHT.get() > 0;
    }
}
