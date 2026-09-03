package app;

import app.core.DecompileOptions;
import app.core.DecompileProgressListener;
import app.core.DecompileStats;
import app.core.GameVersion;
import app.decompiler.DecompilerEngine;
import app.decompiler.DecompilerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decompiler engines: registry selection, real Vineflower output on a
 * tiny JAR, and the always-available javap fallback.
 */
class DecompilerEngineTest {

    @TempDir
    Path tmp;

    @Test
    void registryAutoSelectsAvailableEngine() {
        DecompilerRegistry registry = new DecompilerRegistry();
        DecompilerEngine auto = registry.byName("auto");
        assertNotNull(auto);
        assertTrue(auto.isAvailable());
        assertTrue(registry.availableNames().contains("javap"),
                "javap fallback must always be available");
    }

    @Test
    void vineflowerDecompilesSimpleClass() throws Exception {
        Path jar = TestJars.modernJar(tmp);
        DecompilerRegistry registry = new DecompilerRegistry();
        DecompilerEngine engine = registry.byName("vineflower");
        assertTrue(engine.isAvailable(), "Vineflower must be on the test classpath");

        DecompileStats stats = new DecompileStats();
        DecompileOptions options = DecompileOptions.builder(jar, tmp.resolve("out")).build();
        Map<String, String> out = engine.decompile(jar, GameVersion.classify("1.20.1"),
                options, stats, DecompileProgressListener.silent(), Map.of());

        assertFalse(out.isEmpty(), "Vineflower produced no sources");
        String mc = out.get("net/minecraft/client/Minecraft");
        assertNotNull(mc, "Minecraft class missing from output");
        assertTrue(mc.contains("tick") && mc.contains("getFps"),
                "decompiled source lost methods:\n" + mc.substring(0, Math.min(500, mc.length())));
    }

    @Test
    void javapFallbackAlwaysProducesSources() throws Exception {
        Path jar = TestJars.betaJar(tmp);
        DecompilerEngine engine = new DecompilerRegistry().byName("javap");
        DecompileStats stats = new DecompileStats();
        DecompileOptions options = DecompileOptions.builder(jar, tmp.resolve("out")).build();
        Map<String, String> out = engine.decompile(jar, GameVersion.classify("b1.7.3"),
                options, stats, DecompileProgressListener.silent(), Map.of());
        assertFalse(out.isEmpty());
        assertTrue(out.containsKey("mi"), "flat beta class missing");
    }

    @Test
    void unknownEngineNameFailsFast() {
        assertThrows(IllegalArgumentException.class,
                () -> new DecompilerRegistry().byName("no-such-engine"));
    }
}
