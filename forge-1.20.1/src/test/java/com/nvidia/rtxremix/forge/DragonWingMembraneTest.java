/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.forge;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.forge.compatibility.DragonWingMembraneHelper;
import com.nvidia.rtxremix.forge.entity.RemixCapturingConsumer;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DragonWingMembraneTest {

    @AfterEach
    void tearDown() {
        DragonWingMembraneHelper.resetForTesting();
    }

    @Test
    @DisplayName("DoubleSidedConsumer duplicates quads with reversed winding, inverted normals, and epsilon offset")
    void testDoubleSidedConsumerEmitsBothWindingsWithOffset() {
        RemixCapturingConsumer capturing = new RemixCapturingConsumer();
        capturing.reset(new ResourceLocation("bookofdragons", "textures/entity/dragon/nightfury.png"), "cutout");

        DragonWingMembraneHelper.DoubleSidedConsumer doubleSided =
                new DragonWingMembraneHelper.DoubleSidedConsumer(capturing);

        // Emit 4 vertices of a quad representing a dragon wing membrane panel
        // (x, y, z, r, g, b, a, u, v, overlay, light, nx, ny, nz)
        doubleSided.vertex(0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 0, 0, 0.0f, 1.0f, 0.0f);
        doubleSided.vertex(1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0, 0, 0.0f, 1.0f, 0.0f);
        doubleSided.vertex(1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0, 0, 0.0f, 1.0f, 0.0f);
        doubleSided.vertex(0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 1.0f, 0, 0, 0.0f, 1.0f, 0.0f);

        // Expect exactly 8 vertices captured (4 front quad + 4 back quad)
        assertEquals(8, capturing.getVertexCount());

        float[] px = capturing.getPositionsX();
        float[] py = capturing.getPositionsY();
        float[] pz = capturing.getPositionsZ();
        float[] ny = capturing.getNormalsY();

        // 1. Front quad: v0 (0,0), v1 (1,0), v2 (1,1), v3 (0,1), normal +1.0, y = 1.0
        assertEquals(0.0f, px[0], 1e-4f);
        assertEquals(1.0f, py[0], 1e-4f);
        assertEquals(0.0f, pz[0], 1e-4f);
        assertEquals(1.0f, ny[0], 1e-4f);

        assertEquals(1.0f, px[1], 1e-4f);
        assertEquals(1.0f, py[1], 1e-4f);
        assertEquals(0.0f, pz[1], 1e-4f);
        assertEquals(1.0f, ny[1], 1e-4f);

        assertEquals(1.0f, px[2], 1e-4f);
        assertEquals(1.0f, py[2], 1e-4f);
        assertEquals(1.0f, pz[2], 1e-4f);
        assertEquals(1.0f, ny[2], 1e-4f);

        assertEquals(0.0f, px[3], 1e-4f);
        assertEquals(1.0f, py[3], 1e-4f);
        assertEquals(1.0f, pz[3], 1e-4f);
        assertEquals(1.0f, ny[3], 1e-4f);

        // 2. Back quad with reversed winding: v3, v2, v1, v0
        // Normal inverted to -1.0
        // Position shifted along -normal by EPSILON_OFFSET (1.0 - 1e-4 = 0.9999)
        float expectedBackY = 1.0f - DragonWingMembraneHelper.DoubleSidedConsumer.EPSILON_OFFSET;

        assertEquals(0.0f, px[4], 1e-4f);
        assertEquals(expectedBackY, py[4], 1e-4f);
        assertEquals(1.0f, pz[4], 1e-4f);
        assertEquals(-1.0f, ny[4], 1e-4f);

        assertEquals(1.0f, px[5], 1e-4f);
        assertEquals(expectedBackY, py[5], 1e-4f);
        assertEquals(1.0f, pz[5], 1e-4f);
        assertEquals(-1.0f, ny[5], 1e-4f);

        assertEquals(1.0f, px[6], 1e-4f);
        assertEquals(expectedBackY, py[6], 1e-4f);
        assertEquals(0.0f, pz[6], 1e-4f);
        assertEquals(-1.0f, ny[6], 1e-4f);

        assertEquals(0.0f, px[7], 1e-4f);
        assertEquals(expectedBackY, py[7], 1e-4f);
        assertEquals(0.0f, pz[7], 1e-4f);
        assertEquals(-1.0f, ny[7], 1e-4f);
    }

    @Test
    @DisplayName("When FaunaEngine is ABSENT: graceful fallback, isAvailable() is false, zero errors")
    void testFaunaEngineAbsentFallback() {
        DragonWingMembraneHelper.resetForTesting();

        // Classloader isolated from FaunaEngine classes
        ClassLoader isolatedLoader = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader());
        DragonWingMembraneHelper.initReflection(isolatedLoader);

        assertFalse(DragonWingMembraneHelper.isAvailable());
        assertDoesNotThrow(() -> DragonWingMembraneHelper.renderMembranes(null));
        assertDoesNotThrow(() -> DragonWingMembraneHelper.renderMembranes(new Object()));
    }

    @Test
    @DisplayName("When FaunaEngine is PRESENT: reflection resolves and invokes tessellation dispatch cleanly")
    void testFaunaEnginePresentDispatch(@TempDir Path tempDir) throws Exception {
        DragonWingMembraneHelper.resetForTesting();

        // 1. Generate and compile mock FaunaEngine classes into temp directory
        Path chassisDir = tempDir.resolve("net/magister/liberanimalium/chassis");
        Path renderDir = tempDir.resolve("net/magister/liberanimalium/render");
        Path layerDir = tempDir.resolve("net/magister/liberanimalium/render/layer");
        Files.createDirectories(chassisDir);
        Files.createDirectories(renderDir);
        Files.createDirectories(layerDir);

        Path faunaBasePath = chassisDir.resolve("FaunaBase.java");
        Files.writeString(faunaBasePath,
                "package net.magister.liberanimalium.chassis;\n" +
                "public class FaunaBase {}\n");

        Path wornMembranesPath = layerDir.resolve("WornMembranes.java");
        Files.writeString(wornMembranesPath,
                "package net.magister.liberanimalium.render.layer;\n" +
                "public class WornMembranes {\n" +
                "    public static boolean wasDrawn = false;\n" +
                "    public static void draw(net.magister.liberanimalium.render.RenderContext ctx, java.util.function.Predicate p, float tick, " +
                "                            com.mojang.blaze3d.vertex.VertexConsumer consumer, int light, int overlay) {\n" +
                "        wasDrawn = true;\n" +
                "        consumer.vertex(0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, overlay, light, 0.0f, 1.0f, 0.0f);\n" +
                "        consumer.vertex(1.0f, 1.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, overlay, light, 0.0f, 1.0f, 0.0f);\n" +
                "        consumer.vertex(1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, overlay, light, 0.0f, 1.0f, 0.0f);\n" +
                "        consumer.vertex(0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0.0f, 1.0f, overlay, light, 0.0f, 1.0f, 0.0f);\n" +
                "    }\n" +
                "}\n");

        Path renderContextPath = renderDir.resolve("RenderContext.java");
        Files.writeString(renderContextPath,
                "package net.magister.liberanimalium.render;\n" +
                "public class RenderContext {\n" +
                "    private final com.mojang.blaze3d.vertex.VertexConsumer consumer;\n" +
                "    private final Object entity = new net.magister.liberanimalium.chassis.FaunaBase();\n" +
                "    public RenderContext(com.mojang.blaze3d.vertex.VertexConsumer c) { this.consumer = c; }\n" +
                "    public float partialTick() { return 1.0f; }\n" +
                "    public com.mojang.blaze3d.vertex.VertexConsumer vertexConsumer() { return consumer; }\n" +
                "    public int packedLight() { return 0x00F000F0; }\n" +
                "    public int packedOverlay() { return 0; }\n" +
                "    public Object animatable() { return entity; }\n" +
                "}\n");

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Java compiler must be available in JDK test environment");

        // Compile against current test classpath to resolve com.mojang.blaze3d.vertex.VertexConsumer
        String classPath = System.getProperty("java.class.path");
        int compileResult = compiler.run(null, null, null,
                "-cp", classPath,
                "-d", tempDir.toString(),
                faunaBasePath.toString(),
                wornMembranesPath.toString(),
                renderContextPath.toString()
        );
        assertEquals(0, compileResult, "Mock FaunaEngine compilation must succeed");

        // 2. Load mock classes via URLClassLoader
        URLClassLoader mockLoader = new URLClassLoader(
                new URL[]{ tempDir.toUri().toURL() },
                DragonWingMembraneTest.class.getClassLoader()
        );

        DragonWingMembraneHelper.initReflection(mockLoader);
        assertTrue(DragonWingMembraneHelper.isAvailable(), "Reflection must initialize when FaunaEngine is present");

        // 3. Instantiate mock RenderContext with capturing consumer
        RemixCapturingConsumer capturing = new RemixCapturingConsumer();
        capturing.reset(new ResourceLocation("bookofdragons", "textures/entity/dragon/nightfury.png"), "cutout");

        Class<?> ctxClass = mockLoader.loadClass("net.magister.liberanimalium.render.RenderContext");
        Object mockContext = ctxClass.getConstructor(VertexConsumer.class).newInstance(capturing);

        // 4. Render membranes and verify dispatch
        DragonWingMembraneHelper.renderMembranes(mockContext);

        Class<?> wmClass = mockLoader.loadClass("net.magister.liberanimalium.render.layer.WornMembranes");
        boolean wasDrawn = wmClass.getField("wasDrawn").getBoolean(null);
        assertTrue(wasDrawn, "WornMembranes.draw must be invoked");

        // 5. Verify capturing consumer received 8 vertices (4 front + 4 back)
        assertEquals(8, capturing.getVertexCount(), "Capturing consumer must receive 8 vertices for dual-sided quad");
    }
}
