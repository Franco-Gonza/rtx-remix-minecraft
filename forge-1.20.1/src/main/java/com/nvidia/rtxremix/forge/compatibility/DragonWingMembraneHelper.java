/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.compatibility;

import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.function.Predicate;

/**
 * Compatibility helper for Book of Dragons / Liber Animalium wing membranes in RTX Remix.
 * <p>
 * In FaunaEngine, dragon wing membranes (and tail fin / dorsal sail membranes) are modeled as
 * dynamic polygonal surfaces (MembranePanels) rather than rigid cubes. When software fallback
 * is active, SkeletonRenderer only renders cube bones (producing bare stick wings), while
 * WingMembraneLayer.preRender hides the dummy canvas boxes.
 * <p>
 * This helper hooks after bone cube rendering to invoke WornMembranes.draw via a double-sided
 * vertex consumer, ensuring full wing membranes are emitted and rendered with dual-sided winding
 * for path-traced lighting without backface culling or z-fighting artifacts.
 */
public final class DragonWingMembraneHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger(DragonWingMembraneHelper.class);

    private static volatile boolean initialized = false;
    private static volatile boolean available = false;

    private static Class<?> faunaBaseClass;
    private static Method drawMethod;
    private static Method partialTickMethod;
    private static Method vertexConsumerMethod;
    private static Method packedLightMethod;
    private static Method packedOverlayMethod;
    private static Method animatableMethod;

    private static final Predicate<Object> ALL_PANELS = panel -> true;

    private DragonWingMembraneHelper() {}

    /**
     * Initializes reflection against FaunaEngine classes using the specified ClassLoader.
     * If classLoader is null, uses the thread context classloader or this class's classloader.
     * Gracefully fails with zero errors if FaunaEngine is absent.
     */
    public static synchronized void initReflection(ClassLoader classLoader) {
        if (initialized) return;
        initialized = true;

        ClassLoader cl = classLoader;
        if (cl == null) {
            cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = DragonWingMembraneHelper.class.getClassLoader();
            }
        }

        try {
            Class<?> wornMembranesClass = Class.forName("net.magister.liberanimalium.render.layer.WornMembranes", true, cl);
            Class<?> renderContextClass = Class.forName("net.magister.liberanimalium.render.RenderContext", true, cl);
            faunaBaseClass = Class.forName("net.magister.liberanimalium.chassis.FaunaBase", true, cl);

            for (Method m : wornMembranesClass.getMethods()) {
                if ("draw".equals(m.getName()) && m.getParameterCount() == 6) {
                    drawMethod = m;
                    break;
                }
            }
            if (drawMethod == null) {
                drawMethod = wornMembranesClass.getMethod("draw",
                        renderContextClass, Predicate.class, float.class, VertexConsumer.class, int.class, int.class);
            }

            partialTickMethod = renderContextClass.getMethod("partialTick");
            vertexConsumerMethod = renderContextClass.getMethod("vertexConsumer");
            packedLightMethod = renderContextClass.getMethod("packedLight");
            packedOverlayMethod = renderContextClass.getMethod("packedOverlay");
            animatableMethod = renderContextClass.getMethod("animatable");

            available = true;
            LOGGER.info("[RTX Remix] FaunaEngine WornMembranes reflection initialized successfully.");
        } catch (ClassNotFoundException | LinkageError e) {
            LOGGER.debug("[RTX Remix] FaunaEngine WornMembranes not present on classpath; membrane hook disabled.");
            available = false;
        } catch (Throwable t) {
            LOGGER.warn("[RTX Remix] Failed to initialize FaunaEngine WornMembranes reflection", t);
            available = false;
        }
    }

    /**
     * Returns true if FaunaEngine is present and reflection methods are available.
     */
    public static boolean isAvailable() {
        if (!initialized) {
            initReflection(null);
        }
        return available;
    }

    /**
     * Resets reflection state for unit testing.
     */
    public static synchronized void resetForTesting() {
        initialized = false;
        available = false;
        faunaBaseClass = null;
        drawMethod = null;
        partialTickMethod = null;
        vertexConsumerMethod = null;
        packedLightMethod = null;
        packedOverlayMethod = null;
        animatableMethod = null;
    }

    /**
     * Renders dragon wing membranes for the given FaunaRenderer RenderContext.
     * Scoped strictly to FaunaBase entities (Book of Dragons) and harmlessly returns if unavailable.
     *
     * @param ctx FaunaEngine RenderContext instance
     */
    public static void renderMembranes(Object ctx) {
        if (!initialized) {
            initReflection(null);
        }
        if (!available || ctx == null) {
            return;
        }

        try {
            Object entityObj = animatableMethod.invoke(ctx);
            if (entityObj == null || !faunaBaseClass.isInstance(entityObj)) {
                return;
            }

            VertexConsumer consumer = (VertexConsumer) vertexConsumerMethod.invoke(ctx);
            if (consumer == null) {
                return;
            }

            float partialTick = ((Number) partialTickMethod.invoke(ctx)).floatValue();
            int packedLight = ((Number) packedLightMethod.invoke(ctx)).intValue();
            int packedOverlay = ((Number) packedOverlayMethod.invoke(ctx)).intValue();

            // Wrap consumer with dual-sided winding so membranes are visible from both top and bottom
            DoubleSidedConsumer doubleSidedConsumer = new DoubleSidedConsumer(consumer);

            drawMethod.invoke(null, ctx, ALL_PANELS, partialTick, doubleSidedConsumer, packedLight, packedOverlay);
            doubleSidedConsumer.flush();
        } catch (Throwable t) {
            LOGGER.debug("[RTX Remix] Failed to render dragon wing membranes: {}", t.getMessage());
        }
    }

    /**
     * VertexConsumer decorator that duplicates each 4-vertex quad with reversed winding,
     * inverted surface normals, and a sub-millimeter epsilon offset.
     * Ensures 2D thin surfaces (like dragon wing membranes) are rendered two-sided
     * without ray-tracing backface culling or coplanar z-fighting.
     */
    public static class DoubleSidedConsumer implements VertexConsumer {

        /**
         * 0.1 mm (1e-4 blocks) separation between front and back sheets.
         * Visually imperceptible, but strictly eliminates coplanar z-fighting in path tracing.
         */
        public static final float EPSILON_OFFSET = 1.0e-4f;
        private static final int[] BACK_QUAD_INDICES = {0, 3, 2, 1};

        private final VertexConsumer delegate;

        private final float[] x = new float[4];
        private final float[] y = new float[4];
        private final float[] z = new float[4];
        private final float[] u = new float[4];
        private final float[] v = new float[4];
        private final float[] nx = new float[4];
        private final float[] ny = new float[4];
        private final float[] nz = new float[4];
        private final int[] color = new int[4];
        private final int[] overlay = new int[4];
        private final int[] light = new int[4];

        private float curX, curY, curZ;
        private float curU, curV;
        private float curNx, curNy, curNz;
        private int curColor = 0xFFFFFFFF;
        private int curOverlay = 0;
        private int curLight = 0;
        private int quadCount = 0;

        public DoubleSidedConsumer(VertexConsumer delegate) {
            this.delegate = delegate;
        }

        @Override
        public VertexConsumer vertex(double vx, double vy, double vz) {
            this.curX = (float) vx;
            this.curY = (float) vy;
            this.curZ = (float) vz;
            return this;
        }

        @Override
        public VertexConsumer vertex(Matrix4f matrix, float vx, float vy, float vz) {
            this.curX = matrix.m00() * vx + matrix.m10() * vy + matrix.m20() * vz + matrix.m30();
            this.curY = matrix.m01() * vx + matrix.m11() * vy + matrix.m21() * vz + matrix.m31();
            this.curZ = matrix.m02() * vx + matrix.m12() * vy + matrix.m22() * vz + matrix.m32();
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            this.curColor = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
            return this;
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.curU = u;
            this.curV = v;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            this.curOverlay = (u & 0xFFFF) | ((v & 0xFFFF) << 16);
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            this.curLight = (u & 0xFFFF) | ((v & 0xFFFF) << 16);
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            this.curNx = x;
            this.curNy = y;
            this.curNz = z;
            return this;
        }

        @Override
        public VertexConsumer normal(Matrix3f normalMatrix, float x, float y, float z) {
            float tnx = normalMatrix.m00() * x + normalMatrix.m10() * y + normalMatrix.m20() * z;
            float tny = normalMatrix.m01() * x + normalMatrix.m11() * y + normalMatrix.m21() * z;
            float tnz = normalMatrix.m02() * x + normalMatrix.m12() * y + normalMatrix.m22() * z;
            float lenSq = tnx * tnx + tny * tny + tnz * tnz;
            if (lenSq > 1.0E-6f) {
                float inv = (float) (1.0 / Math.sqrt(lenSq));
                tnx *= inv;
                tny *= inv;
                tnz *= inv;
            }
            this.curNx = tnx;
            this.curNy = tny;
            this.curNz = tnz;
            return this;
        }

        @Override
        public void vertex(float vx, float vy, float vz, float red, float green, float blue, float alpha,
                           float u, float v, int overlay, int light, float normalX, float normalY, float normalZ) {
            if (quadCount < 4) {
                this.x[quadCount] = vx;
                this.y[quadCount] = vy;
                this.z[quadCount] = vz;
                this.u[quadCount] = u;
                this.v[quadCount] = v;
                this.nx[quadCount] = normalX;
                this.ny[quadCount] = normalY;
                this.nz[quadCount] = normalZ;
                this.color[quadCount] = (((int)(alpha * 255.0f) & 0xFF) << 24)
                        | (((int)(red * 255.0f) & 0xFF) << 16)
                        | (((int)(green * 255.0f) & 0xFF) << 8)
                        | ((int)(blue * 255.0f) & 0xFF);
                this.overlay[quadCount] = overlay;
                this.light[quadCount] = light;
                quadCount++;
            }
            if (quadCount == 4) {
                emitDualSidedQuad();
            }
        }

        @Override
        public void endVertex() {
            if (quadCount < 4) {
                x[quadCount] = curX;
                y[quadCount] = curY;
                z[quadCount] = curZ;
                u[quadCount] = curU;
                v[quadCount] = curV;
                nx[quadCount] = curNx;
                ny[quadCount] = curNy;
                nz[quadCount] = curNz;
                color[quadCount] = curColor;
                overlay[quadCount] = curOverlay;
                light[quadCount] = curLight;
                quadCount++;
            }
            if (quadCount == 4) {
                emitDualSidedQuad();
            }
        }

        private void emitDualSidedQuad() {
            // 1. Emit front quad: 0 -> 1 -> 2 -> 3
            for (int i = 0; i < 4; i++) {
                emitVertex(x[i], y[i], z[i], nx[i], ny[i], nz[i], i);
            }
            // 2. Emit back quad with diagonal-aligned reversed winding: 0 -> 3 -> 2 -> 1,
            // inverted normals, and a sub-millimeter epsilon offset along -normal.
            // When indexed canonically by (0, 2, 1, 0, 3, 2), both sheets split across diagonal (v0, v2),
            // strictly eliminating crossing-diagonal penetration, geometry deformation, and z-fighting.
            for (int idx = 0; idx < 4; idx++) {
                int i = BACK_QUAD_INDICES[idx];
                float ox = x[i] - nx[i] * EPSILON_OFFSET;
                float oy = y[i] - ny[i] * EPSILON_OFFSET;
                float oz = z[i] - nz[i] * EPSILON_OFFSET;
                emitVertex(ox, oy, oz, -nx[i], -ny[i], -nz[i], i);
            }
            quadCount = 0;
        }

        private void emitVertex(float vx, float vy, float vz, float normX, float normY, float normZ, int i) {
            int c = color[i];
            int a = (c >> 24) & 0xFF;
            int r = (c >> 16) & 0xFF;
            int g = (c >> 8) & 0xFF;
            int b = c & 0xFF;
            int ov = overlay[i];
            int uo = ov & 0xFFFF;
            int vo = (ov >> 16) & 0xFFFF;
            int li = light[i];
            int ul = li & 0xFFFF;
            int vl = (li >> 16) & 0xFFFF;

            delegate.vertex(vx, vy, vz)
                    .color(r, g, b, a)
                    .uv(u[i], v[i])
                    .overlayCoords(uo, vo)
                    .uv2(ul, vl)
                    .normal(normX, normY, normZ)
                    .endVertex();
        }

        public void flush() {
            if (quadCount > 0) {
                for (int i = 0; i < quadCount; i++) {
                    emitVertex(x[i], y[i], z[i], nx[i], ny[i], nz[i], i);
                }
                quadCount = 0;
            }
        }

        @Override
        public void defaultColor(int r, int g, int b, int a) {
            delegate.defaultColor(r, g, b, a);
        }

        @Override
        public void unsetDefaultColor() {
            delegate.unsetDefaultColor();
        }
    }
}
