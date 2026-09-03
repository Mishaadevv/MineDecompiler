package app;

import app.bytecode.BytecodeAnalyzer;
import app.bytecode.BytecodeClass;
import app.bytecode.ClassGraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bytecode analysis (ASM): classes, methods, fields, hierarchy,
 * references and the dependency graph used for navigation.
 */
class BytecodeAnalyzerTest {

    @TempDir
    Path tmp;

    @Test
    void analyzesModernFixture() throws Exception {
        Path jar = TestJars.modernJar(tmp);
        Map<String, BytecodeClass> classes = BytecodeAnalyzer.analyzeJar(jar);
        assertTrue(classes.size() >= 2, "expected >= 2 classes, got " + classes.size());

        BytecodeClass mc = classes.get("net/minecraft/client/Minecraft");
        assertNotNull(mc, "Minecraft class missing");
        assertEquals("net.minecraft.client.Minecraft", mc.getClassName());
        assertTrue(mc.getMethods().stream().anyMatch(m -> m.getName().equals("tick")),
                "tick() not found");
        assertTrue(mc.getMethods().stream().anyMatch(m -> m.getName().equals("getFps")),
                "getFps() not found");
        assertTrue(mc.getFields().stream().anyMatch(f -> f.getName().equals("fps")),
                "fps field not found");
    }

    @Test
    void graphCapturesReferencesAndHierarchy() throws Exception {
        Path jar = TestJars.modernJar(tmp);
        Map<String, BytecodeClass> classes = BytecodeAnalyzer.analyzeJar(jar);
        ClassGraph graph = new ClassGraph();
        graph.addAll(classes.values());

        assertEquals(classes.size(), graph.size());
        // World references Minecraft (field + ctor + call).
        assertTrue(graph.getReferences("net/minecraft/world/World")
                .contains("net/minecraft/client/Minecraft"), "World -> Minecraft edge missing");
        assertTrue(graph.getReferencedBy("net/minecraft/client/Minecraft")
                .contains("net/minecraft/world/World"), "reverse edge missing");
        // Superclass chain of World ends at java/lang/Object.
        assertTrue(graph.superclassChain("net/minecraft/world/World")
                .contains("java/lang/Object"));
    }

    @Test
    void corruptClassNeverLosesEntries() throws Exception {
        Path jar = TestJars.buildJar(tmp, "mixed.jar",
                java.util.List.of(new TestJars.Source("ok.Hello", """
                        package ok;
                        public class Hello {}
                        """)),
                null,
                Map.of("broken/Broken.class", new byte[]{0x00, 0x01, 0x02, 0x03}));
        Map<String, BytecodeClass> classes = BytecodeAnalyzer.analyzeJar(jar);
        assertTrue(classes.containsKey("ok/Hello"), "valid class missing");
        assertTrue(classes.containsKey("broken/Broken"), "broken class must be kept as placeholder");
    }
}
