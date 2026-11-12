/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge.runtime;

import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Extracts the bundled native runtime (remix-sdk, d3d9.dll, remixbridge.dll, rtx.conf)
 * from the mod JAR into the game directory on launch if missing or updated.
 */
public final class ForgeNativeExtractor {
    private static final Logger LOGGER = LoggerFactory.getLogger("rtxremix/NativeExtractor");
    private static final String VERSION_MARKER = ".rtx-remix-version";
    private static final String NATIVE_ROOT = "native/";

    private ForgeNativeExtractor() {}

    public static void ensureExtracted() {
        Path gameDir = FMLPaths.GAMEDIR.get();
        Path sdkDir = gameDir.resolve("remix-sdk");
        Path d3d9 = sdkDir.resolve("d3d9.dll");
        Path bridge = sdkDir.resolve("remixbridge.dll");
        Path rtxConf = gameDir.resolve("rtx.conf");

        // Check if override is supplied via JVM argument
        String explicitRuntime = System.getProperty("rtxremix.forge.runtime");
        if (explicitRuntime != null && !explicitRuntime.isBlank()) {
            LOGGER.info("Using explicitly specified Remix runtime directory: {}", explicitRuntime);
            return;
        }

        if (Files.exists(d3d9) && Files.exists(bridge) && Files.exists(rtxConf)) {
            LOGGER.debug("RTX Remix runtime and configuration already present in game directory.");
            return;
        }

        LOGGER.info("Extracting bundled RTX Remix runtime into {}", gameDir.toAbsolutePath());
        try {
            extractFromJar(gameDir);
        } catch (Exception e) {
            LOGGER.error("Failed to extract RTX Remix native runtime from JAR: {}", e.getMessage(), e);
        }
    }

    private static Path resolveJarPath() {
        try {
            net.minecraftforge.fml.ModList ml = net.minecraftforge.fml.ModList.get();
            if (ml != null) {
                var mfi = ml.getModFileById("rtxremix");
                if (mfi != null && mfi.getFile() != null) {
                    Path p = mfi.getFile().getFilePath();
                    if (p != null && Files.isRegularFile(p)) {
                        return p;
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            String pathStr = ForgeNativeExtractor.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath();
            if (pathStr != null) {
                pathStr = java.net.URLDecoder.decode(pathStr, java.nio.charset.StandardCharsets.UTF_8);
                int jarIdx = pathStr.toLowerCase().indexOf(".jar");
                if (jarIdx != -1) {
                    pathStr = pathStr.substring(0, jarIdx + 4);
                }
                if (pathStr.startsWith("file:")) {
                    pathStr = pathStr.substring(5);
                }
                if (pathStr.startsWith("/") && System.getProperty("os.name").toLowerCase().contains("win")) {
                    pathStr = pathStr.substring(1);
                }
                Path p = Paths.get(pathStr);
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("Failed resolving JAR path: {}", t.getMessage());
        }
        return null;
    }

    private static void extractFromJar(Path gameDir) throws IOException {
        Path jarPath = resolveJarPath();

        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            LOGGER.warn("Could not resolve valid JAR path; skipping JAR unpack for {}", jarPath);
            return;
        }

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(NATIVE_ROOT) || entry.isDirectory()) {
                    continue;
                }

                String relativeName = name.substring(NATIVE_ROOT.length());
                Path targetPath = gameDir.resolve(relativeName);

                Files.createDirectories(targetPath.getParent());
                try (InputStream in = jar.getInputStream(entry)) {
                    Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
