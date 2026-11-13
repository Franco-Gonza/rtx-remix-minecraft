/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Extracts layer-level properties required by the RTX Remix entity capture pipeline.
 */
public final class RenderLayerUtils {

    private static final Logger LOGGER = LoggerFactory.getLogger(RenderLayerUtils.class);

    public enum SurfaceMode {
        OPAQUE,
        CUTOUT,
        TRANSLUCENT,
        EMISSIVE,
        GLASS
    }

    public enum Topology {
        QUADS,
        TRIANGLE_STRIP
    }

    public static final ResourceLocation UNTEXTURED_LAYER_ID =
            new ResourceLocation("rtxremix", "untextured_entity_layer");

    private RenderLayerUtils() {}

    private static final Map<RenderType, Optional<ResourceLocation>> capturableTextureCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<RenderType, Boolean> streamedPerFrameCache =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static Field stateField = null;
    private static Field depthTestStateField = null;
    private static Field textureStateField = null;
    private static Method cutoutTextureMethod = null;
    private static boolean reflectionInitialized = false;

    private static synchronized void initReflection(Class<?> layerClass) {
        if (reflectionInitialized) return;
        try {
            Class<?> curr = layerClass;
            while (curr != null && curr != Object.class) {
                try {
                    stateField = curr.getDeclaredField("state");
                    stateField.setAccessible(true);
                    break;
                } catch (NoSuchFieldException e) {
                    curr = curr.getSuperclass();
                }
            }

            if (stateField != null) {
                Class<?> compositeStateClass = stateField.getType();
                for (Field f : compositeStateClass.getDeclaredFields()) {
                    if (f.getName().equals("depthTestState") || f.getType().getSimpleName().contains("DepthTestState")) {
                        depthTestStateField = f;
                        depthTestStateField.setAccessible(true);
                    } else if (f.getName().equals("textureState") || f.getType().getSimpleName().contains("TextureState")) {
                        textureStateField = f;
                        textureStateField.setAccessible(true);
                    }
                }

                if (textureStateField != null) {
                    Class<?> textureStateClass = textureStateField.getType();
                    for (Method m : textureStateClass.getMethods()) {
                        if ("cutoutTexture".equals(m.getName())) {
                            cutoutTextureMethod = m;
                            cutoutTextureMethod.setAccessible(true);
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Reflection init for RenderType state: {}", e.getMessage());
        } finally {
            reflectionInitialized = true;
        }
    }

    public static Optional<ResourceLocation> capturableTexture(RenderType layer) {
        return capturableTextureCache.computeIfAbsent(layer, RenderLayerUtils::capturableTextureUncached);
    }

    @SuppressWarnings("unchecked")
    private static Optional<ResourceLocation> capturableTextureUncached(RenderType layer) {
        if (layer == null) return Optional.empty();

        initReflection(layer.getClass());
        if (stateField != null) {
            try {
                Object state = stateField.get(layer);
                if (state != null) {
                    if (depthTestStateField != null) {
                        Object depthState = depthTestStateField.get(state);
                        if (depthState != null && depthState.toString().contains("no_depth_test")) {
                            return Optional.empty();
                        }
                    }

                    if (textureStateField != null) {
                        Object textureBase = textureStateField.get(state);
                        if (textureBase != null) {
                            if (cutoutTextureMethod != null) {
                                return (Optional<ResourceLocation>) cutoutTextureMethod.invoke(textureBase);
                            }
                            Method m = textureBase.getClass().getDeclaredMethod("cutoutTexture");
                            m.setAccessible(true);
                            return (Optional<ResourceLocation>) m.invoke(textureBase);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Fallback name heuristic if reflection cannot resolve
        String name = layer.toString();
        int start = name.indexOf('[');
        int end = name.indexOf(']');
        if (start >= 0 && end > start) {
            String candidate = name.substring(start + 1, end).trim();
            ResourceLocation loc = ResourceLocation.tryParse(candidate);
            if (loc != null) {
                return Optional.of(loc);
            }
            int lastColon = candidate.lastIndexOf(':');
            if (lastColon > 0) {
                int secondLastColon = candidate.lastIndexOf(':', lastColon - 1);
                if (secondLastColon >= 0) {
                    String resStr = candidate.substring(secondLastColon + 1);
                    loc = ResourceLocation.tryParse(resStr);
                    if (loc != null) {
                        return Optional.of(loc);
                    }
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<Topology> topology(RenderType layer) {
        VertexFormat.Mode mode = layer.mode();
        if (mode == VertexFormat.Mode.QUADS) {
            return Optional.of(Topology.QUADS);
        } else if (mode == VertexFormat.Mode.TRIANGLE_STRIP) {
            return Optional.of(Topology.TRIANGLE_STRIP);
        }
        return Optional.empty();
    }

    public static boolean isCapturableUntexturedLayer(RenderType layer) {
        return capturableTexture(layer).isEmpty()
                && topology(layer).filter(t -> t == Topology.TRIANGLE_STRIP).isPresent();
    }

    public static boolean isStreamedPerFrame(RenderType layer) {
        return streamedPerFrameCache.computeIfAbsent(layer, RenderLayerUtils::isStreamedPerFrameUncached);
    }

    private static boolean isStreamedPerFrameUncached(RenderType layer) {
        boolean beaconBeam = layer.toString().contains("beacon_beam");
        return topology(layer).filter(t -> t == Topology.TRIANGLE_STRIP).isPresent() || beaconBeam;
    }

    public static SurfaceMode surfaceMode(RenderType layer) {
        if (layer == null) return SurfaceMode.CUTOUT;

        String name = layer.toString().toLowerCase();
        if (name.contains("end_portal") || name.contains("end_gateway")) {
            return SurfaceMode.OPAQUE;
        }
        if (name.contains("eyes") || name.contains("emissive")) {
            return SurfaceMode.EMISSIVE;
        }
        if (name.contains("text")) {
            return SurfaceMode.CUTOUT;
        }
        if (name.contains("translucent") || name.contains("crumbling") || name.contains("water")) {
            return SurfaceMode.TRANSLUCENT;
        }
        if (name.contains("solid")) {
            return SurfaceMode.OPAQUE;
        }
        return SurfaceMode.CUTOUT;
    }
}
