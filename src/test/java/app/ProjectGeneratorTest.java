package app;

import app.core.DecompileOptions;
import app.core.DecompileProgressListener;
import app.core.DecompiledProject;
import app.pipeline.DecompilationPipeline;
import app.project.ExportManager;
import app.core.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end project generation: input JAR -&gt; pipeline -&gt; exported
 * source tree with the required layout. No class may be lost.
 */
class ProjectGeneratorTest {

    @TempDir
    Path tmp;

    @Test
    void pipelineProducesRequiredLayout() throws Exception {
        Path jar = TestJars.modernJar(tmp);
        Path out = tmp.resolve("Minecraft-1.20.1-Decompiled");

        DecompileOptions options = DecompileOptions.builder(jar, out)
                .decompiler("javap") // deterministic + fast; Vineflower covered separately
                .autoDownloadMappings(false) // hermetic: no network in unit tests
                .build();
        DecompiledProject project = new DecompilationPipeline()
                .run(options, DecompileProgressListener.silent());

        assertTrue(Files.isDirectory(out.resolve("src/main/java")), "src/main/java missing");
        assertTrue(Files.isDirectory(out.resolve("resources")), "resources missing");
        assertTrue(Files.isDirectory(out.resolve("mappings")), "mappings missing");
        assertTrue(Files.isDirectory(out.resolve("metadata")), "metadata missing");
        assertTrue(Files.isRegularFile(out.resolve("README.md")), "README.md missing");
        assertTrue(Files.isRegularFile(out.resolve("reports/report.txt")), "report missing");
        assertFalse(project.getClassToSource().isEmpty(), "no classes exported");

        // Original JAR untouched.
        assertTrue(Files.isRegularFile(jar), "input JAR must still exist");
    }

    @Test
    void vineflowerPipelineKeepsAllClasses() throws Exception {
        Path jar = TestJars.modernJar(tmp);
        Path out = tmp.resolve("vf-project");
        DecompileOptions options = DecompileOptions.builder(jar, out)
                .decompiler("vineflower")
                .autoDownloadMappings(false) // hermetic: no network in unit tests
                .build();
        DecompiledProject project = new DecompilationPipeline()
                .run(options, DecompileProgressListener.silent());

        assertTrue(project.getClassToSource().containsKey("net/minecraft/client/Minecraft"));
        assertTrue(project.getClassToSource().containsKey("net/minecraft/world/World"));
        // Every exported file exists on disk.
        for (Path p : project.getClassToSource().values()) {
            assertTrue(Files.isRegularFile(p), "missing file: " + p);
        }
    }

    @Test
    void openProjectReadsBackExport() throws Exception {
        Path jar = TestJars.legacyJar(tmp);
        Path out = tmp.resolve("legacy-project");
        DecompileOptions options = DecompileOptions.builder(jar, out).decompiler("javap")
                .autoDownloadMappings(false).build(); // hermetic: no network in unit tests
        new DecompilationPipeline().run(options, DecompileProgressListener.silent());

        Project opened = ExportManager.openProject(out);
        assertFalse(opened.getClasses().isEmpty(), "opened project has no classes");
        assertTrue(opened.getClasses().stream().anyMatch(c -> c.contains("Minecraft")));
    }

    @Test
    void outputIsNeverHardcoded() {
        Path custom = tmp.resolve("totally-custom-dir-123");
        DecompileOptions options = DecompileOptions.builder(tmp.resolve("x.jar"), custom).build();
        assertEquals(custom, options.getOutputDirectory(),
                "output directory must come from options, never hard-coded");
    }
}
