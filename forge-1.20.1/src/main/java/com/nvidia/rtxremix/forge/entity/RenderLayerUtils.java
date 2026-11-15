/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.entity;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.nvidia.rtxremix.forge.mixin.CompositeRenderTypeAccessor;
import com.nvidia.rtxremix.forge.mixin.CompositeStateAccessor;
import com.nvidia.rtxremix.forge.mixin.EmptyTextureStateShardInvoker;
import com.nvidia.rtxremix.forge.mixin.TextureStateShardAccessor;
import net.minecraft.client.renderer.RenderStateShard;
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

    public static Optional<ResourceLocation> capturableTexture(RenderType layer) {
        return capturableTextureCache.computeIfAbsent(layer, RenderLayerUtils::capturableTextureUncached);
    }

    private static Optional<ResourceLocation> capturableTextureUncached(RenderType layer) {
        if (layer == null) {
            return Optional.empty();
        }

        // 1. Primary path: Mixin accessors (fast, reobf-safe at runtime)
        if ((Object) layer instanceof CompositeRenderTypeAccessor multiPhase) {
            try {
                RenderType.CompositeState phases = multiPhase.rtxremix$getState();
                if ((Object) phases instanceof CompositeStateAccessor stateAccessor) {
                    RenderStateShard.EmptyTextureStateShard textureBase = stateAccessor.rtxremix$getTextureState();
                    if ((Object) textureBase instanceof EmptyTextureStateShardInvoker invoker) {
                        Optional<ResourceLocation> loc = invoker.rtxremix$invokeCutoutTexture();
                        if (loc != null && loc.isPresent()) {
                            return loc;
                        }
                    }
                    if ((Object) textureBase instanceof TextureStateShardAccessor textureShard) {
                        Optional<ResourceLocation> loc = textureShard.rtxremix$getTexture();
                        if (loc != null && loc.isPresent()) {
                            return loc;
                        }
                    }
                }
            } catch (Throwable t) {
                LOGGER.debug("Failed to extract texture via accessor: {}", t.getMessage());
            }
        }

        // 2. Secondary path: Reflection supporting both dev (Mojmap) and production (SRG) field names
        try {
            Class<?> clazz = layer.getClass();
            Field stateField = findField(clazz, "state", "f_110511_");
            if (stateField != null) {
                stateField.setAccessible(true);
                Object state = stateField.get(layer);
                if (state != null) {
                    Field textureStateField = findField(state.getClass(), "textureState", "f_110592_");
                    if (textureStateField != null) {
                        textureStateField.setAccessible(true);
                        Object textureState = textureStateField.get(state);
                        if (textureState != null) {
                            // Method cutoutTexture / m_142706_
                            Method cutoutMethod = findMethod(textureState.getClass(), "cutoutTexture", "m_142706_");
                            if (cutoutMethod != null) {
                                cutoutMethod.setAccessible(true);
                                @SuppressWarnings("unchecked")
                                Optional<ResourceLocation> loc = (Optional<ResourceLocation>) cutoutMethod.invoke(textureState);
                                if (loc != null && loc.isPresent()) {
                                    return loc;
                                }
                            }
                            // Field texture / f_110328_
                            Field textureField = findField(textureState.getClass(), "texture", "f_110328_");
                            if (textureField != null) {
                                textureField.setAccessible(true);
                                @SuppressWarnings("unchecked")
                                Optional<ResourceLocation> loc = (Optional<ResourceLocation>) textureField.get(textureState);
                                if (loc != null && loc.isPresent()) {
                                    return loc;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Secondary reflection texture extraction failed: {}", t.getMessage());
        }

        // 3. Fallback name heuristic if reflection cannot resolve
        String name = layer.toString();
        if (name.contains("species_cutout_") || name.contains("species_translucent_")) {
            String prefix = name.contains("species_cutout_") ? "species_cutout_" : "species_translucent_";
            String species = name.substring(name.indexOf(prefix) + prefix.length()).trim();
            int sep = species.indexOf(':');
            if (sep > 0) species = species.substring(0, sep).trim();
            int brk = species.indexOf('[');
            if (brk > 0) species = species.substring(0, brk).trim();
            if (!species.isEmpty()) {
                return Optional.of(new ResourceLocation("bookofdragons", "textures/entity/genetics/" + species + "/base.png"));
            }
        }

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

    private static Field findField(Class<?> clazz, String... names) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (String name : names) {
                try {
                    return current.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {}
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String... names) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (String name : names) {
                try {
                    return current.getDeclaredMethod(name);
                } catch (NoSuchMethodException ignored) {}
            }
            current = current.getSuperclass();
        }
        return null;
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
