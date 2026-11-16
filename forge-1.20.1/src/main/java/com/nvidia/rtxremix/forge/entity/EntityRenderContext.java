/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-local context tracking whether an RTX Remix entity capture is open
 * and assigning rigid bone transform indices.
 */
public final class EntityRenderContext {

    private static final ThreadLocal<EntityRenderContext> CURRENT = ThreadLocal.withInitial(EntityRenderContext::new);

    public static final int NO_BLOCK_ITEM = -1;

    private int depth;
    private boolean remixPass;
    private InteractionHand activeHand;
    private LivingEntity heldItemHolder;
    private int blockItemLayer = NO_BLOCK_ITEM;

    private final Vector3f eye = new Vector3f();
    private boolean eyeKnown;

    private int boneCounter;
    private final List<Matrix4f> boneTransforms = new ArrayList<>();
    private final List<InteractionHand> transformHands = new ArrayList<>();
    private int bonePoolHighWater = 0;

    public static EntityRenderContext get() {
        return CURRENT.get();
    }

    public void begin(boolean remixPass) {
        if (depth++ > 0) {
            return;
        }
        this.remixPass = remixPass;
        this.activeHand = null;
        this.heldItemHolder = null;
        this.blockItemLayer = NO_BLOCK_ITEM;
        this.eyeKnown = false;
        this.boneCounter = 0;
    }

    public void end() {
        if (depth == 0 || --depth > 0) {
            return;
        }
        closeCapture();
    }

    public void reset() {
        depth = 0;
        closeCapture();
    }

    private void closeCapture() {
        this.remixPass = false;
        this.activeHand = null;
        this.heldItemHolder = null;
        this.blockItemLayer = NO_BLOCK_ITEM;
        this.eyeKnown = false;
    }

    public boolean isActive() {
        return depth > 0;
    }

    public boolean isRemixPass() {
        return remixPass;
    }

    public int getBlockItemLayer() {
        return blockItemLayer;
    }

    public void setBlockItemLayer(int blockItemLayer) {
        this.blockItemLayer = blockItemLayer;
    }

    public Vector3fc getEye() {
        return eyeKnown ? eye : null;
    }

    public void setEye(float x, float y, float z) {
        eye.set(x, y, z);
        eyeKnown = true;
    }

    public int pushBone(Matrix4f pose) {
        int index = boneCounter++;
        if (index < boneTransforms.size()) {
            boneTransforms.get(index).set(pose);
            transformHands.set(index, activeHand);
        } else {
            boneTransforms.add(new Matrix4f(pose));
            transformHands.add(activeHand);
        }
        if (boneCounter > bonePoolHighWater) {
            bonePoolHighWater = boneCounter;
        }
        return index;
    }

    public int currentBoneIndex() {
        return boneCounter > 0 ? boneCounter - 1 : -1;
    }

    public int getBoneCount() {
        return boneCounter;
    }

    public Matrix4f getBoneTransform(int index) {
        return boneTransforms.get(index);
    }

    public float[] snapshotBoneTransforms(float[] target) {
        int count = boneCounter;
        int requiredFloats = count * 12;
        if (target == null || target.length < requiredFloats) {
            target = new float[requiredFloats];
        }
        for (int i = 0; i < count; i++) {
            Matrix4f m = boneTransforms.get(i);
            int base = i * 12;
            target[base]      = m.m00();
            target[base + 1]  = m.m10();
            target[base + 2]  = m.m20();
            target[base + 3]  = m.m30();
            target[base + 4]  = m.m01();
            target[base + 5]  = m.m11();
            target[base + 6]  = m.m21();
            target[base + 7]  = m.m31();
            target[base + 8]  = m.m02();
            target[base + 9]  = m.m12();
            target[base + 10] = m.m22();
            target[base + 11] = m.m32();
        }
        return target;
    }
}
