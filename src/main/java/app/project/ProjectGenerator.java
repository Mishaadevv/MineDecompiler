package app.project;

import app.core.DecompileProgressListener;
import app.core.DecompileStats;
import app.core.GameVersion;
import app.mappings.MappingSet;
import app.reconstruction.PackageReconstructor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes the final Gradle-ready project tree (spec section 7):
 * output/ src/main/java resources/ mappings/ metadata/ reports/ README.md
 */
public final class ProjectGenerator {

    private ProjectGenerator() {
    }

    public record GeneratedProject(Path root, Path sourcesRoot, Map<String, Path> classToSource) {
    }

    public static GeneratedProject generate(Path outputDir,
                                            GameVersion version,
                                            String profileId,
                                            String decompilerName,
                                            Map<String, String> sources,
                                            MappingSet mappings,
                                            DecompileStats stats,
                                            List<String> failedClasses,
                                            DecompileProgressListener listener,
                                            String inputJarName,
                                            String inputSha256) throws IOException {
        Path root = outputDir.toAbsolutePath();
        Path sourcesRoot = root.resolve("src/main/java");
        Path resourcesRoot = root.resolve("resources");
        Path mappingsDir = root.resolve("mappings");
        Path metadataDir = root.resolve("metadata");
        Path reportsDir = root.resolve("reports");
        Files.createDirectories(sourcesRoot);
        Files.createDirectories(resourcesRoot);
        Files.createDirectories(mappingsDir);
        Files.createDirectories(metadataDir);
        Files.createDirectories(reportsDir);

        Map<String, Path> classToSource = new LinkedHashMap<>();
        List<String> entries = new ArrayList<>(sources.keySet());
        int total = Math.max(1, entries.size());
        int i = 0;
        for (String internal : entries) {
            if (listener.isCancelled()) {
                break;
            }
            Path dest = PackageReconstructor.toSourcePath(sourcesRoot, internal);
            // Avoid inner-class file collisions: foo/Bar$Inner -> foo/Bar.java already
            // written by the outer class pass. Keep the first (outer) file.
            if (Files.exists(dest) && internal.contains("$")) {
                i++;
                continue;
            }
            Files.createDirectories(dest.getParent());
            Files.writeString(dest, sources.get(internal), StandardCharsets.UTF_8);
            classToSource.put(internal, dest);
            i++;
            if (i % 500 == 0 || i == total) {
                listener.onProgress(0.98 * i / total + 0.0, internal, i, total);
            }
        }

        writeMetadata(metadataDir, version, profileId, decompilerName, stats,
                inputJarName, inputSha256, classToSource.size(), failedClasses);
        writeMappingsCopy(mappingsDir, mappings);
        writeReport(reportsDir, stats, failedClasses, version, profileId, decompilerName);
        writeReadme(root, version, profileId, decompilerName, stats);
        writeBuildFile(root);
        return new GeneratedProject(root, sourcesRoot, classToSource);
    }

    private static void writeMetadata(Path metadataDir, GameVersion version, String profileId,
                                      String decompiler, DecompileStats stats, String jarName,
                                      String sha256, int written, List<String> failed) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tool", "mcdecompiler");
        meta.put("generatedAt", Instant.now().toString());
        meta.put("minecraftVersion", version.getId());
        meta.put("era", version.getEra().name());
        meta.put("profile", profileId);
        meta.put("decompiler", decompiler);
        meta.put("inputJar", jarName);
        meta.put("inputSha256", sha256);
        meta.put("classesWritten", written);
        meta.put("failed", failed);
        meta.put("stats", Map.of(
                "analyzed", stats.getClassesAnalyzed(),
                "decompiled", stats.getClassesDecompiled(),
                "mappingsApplied", stats.getMappingsApplied(),
                "errors", stats.getErrors(),
                "warnings", stats.getWarnings(),
                "elapsedSeconds", stats.getElapsedSeconds()));
        Files.writeString(metadataDir.resolve("decompilation.json"),
                gson.toJson(meta), StandardCharsets.UTF_8);
        // Machine-readable class index for the GUI quick-open.
        StringBuilder idx = new StringBuilder();
        // (written by caller via classToSource scan to avoid huge strings here)
        Files.writeString(metadataDir.resolve("version.txt"),
                version.getId() + " [" + version.getEra() + "] profile=" + profileId + "\n",
                StandardCharsets.UTF_8);
    }

    private static void writeMappingsCopy(Path mappingsDir, MappingSet mappings) throws IOException {
        Path used = mappingsDir.resolve("applied-mappings.txt");
        StringBuilder sb = new StringBuilder();
        sb.append("# Mappings applied by mcdecompiler (unified view)\n");
        for (Map.Entry<String, String> e : mappings.getMetadata().entrySet()) {
            sb.append("# ").append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        sb.append("# classes: ").append(mappings.getClasses().size()).append('\n');
        int n = 0;
        for (Map.Entry<String, String> e : mappings.getClasses().entrySet()) {
            sb.append(e.getKey()).append(" -> ").append(e.getValue()).append('\n');
            if (++n >= 50_000) {
                sb.append("# ... truncated\n");
                break;
            }
        }
        Files.writeString(used, sb.toString(), StandardCharsets.UTF_8);
        Path readme = mappingsDir.resolve("README.txt");
        if (!Files.exists(readme)) {
            Files.writeString(readme,
                    "Drop mapping files here (Mojang client.txt/server.txt, Tiny *.tiny, SRG/TSRG *.srg)\n"
                            + "or pass --mappings <file|dir>. Re-run to apply them.\n",
                    StandardCharsets.UTF_8);
        }
    }

    private static void writeReport(Path reportsDir, DecompileStats stats,
                                    List<String> failed, GameVersion version,
                                    String profileId, String decompiler) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("Decompilation report\n====================\n");
        sb.append("Version: ").append(version).append('\n');
        sb.append("Profile: ").append(profileId).append('\n');
        sb.append("Decompiler: ").append(decompiler).append('\n');
        sb.append('\n').append(stats.summary()).append('\n');
        if (!failed.isEmpty()) {
            sb.append("\nFailed classes (fallback sources generated):\n");
            for (String f : failed) {
                sb.append(" - ").append(f).append('\n');
            }
        }
        if (!stats.getErrorDetails().isEmpty()) {
            sb.append("\nErrors:\n");
            for (String e : stats.getErrorDetails()) {
                sb.append(" ! ").append(e).append('\n');
            }
        }
        if (!stats.getWarningDetails().isEmpty()) {
            int n = 0;
            sb.append("\nWarnings (first 200):\n");
            for (String w : stats.getWarningDetails()) {
                sb.append(" * ").append(w).append('\n');
                if (++n >= 200) break;
            }
        }
        Files.writeString(reportsDir.resolve("report.txt"), sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeReadme(Path root, GameVersion version, String profileId,
                                    String decompiler, DecompileStats stats) throws IOException {
        String readme = "# Decompiled Minecraft " + version.getId() + "\n\n"
                + "Generated by **mcdecompiler** (Minecraft Source Reconstructor).\n\n"
                + "- Minecraft version: `" + version.getId() + "` (" + version.getEra() + ")\n"
                + "- Profile: `" + profileId + "`\n"
                + "- Decompiler: `" + decompiler + "`\n\n"
                + "## Layout\n\n"
                + "- `src/main/java` - decompiled sources (package structure preserved)\n"
                + "- `resources/` - non-class JAR resources\n"
                + "- `mappings/` - mappings used + drop zone for your own\n"
                + "- `metadata/` - provenance (`decompilation.json`)\n"
                + "- `reports/` - `report.txt` with errors/warnings\n\n"
                + "## Notes\n\n"
                + "This is a best-effort reconstruction: where official mappings are missing, "
                + "names are synthetic and marked with `NOTE: no mappings were available`. "
                + "Semantics match the original bytecode; names may differ from Mojang's sources.\n\n"
                + "## Stats\n\n```\n" + stats.summary() + "\n```\n";
        Files.writeString(root.resolve("README.md"), readme, StandardCharsets.UTF_8);
    }

    private static void writeBuildFile(Path root) throws IOException {
        Path gradle = root.resolve("build.gradle");
        if (Files.exists(gradle)) {
            return;
        }
        Files.writeString(gradle,
                "plugins { id 'java' }\n"
                        + "repositories { mavenCentral() }\n"
                        + "// Decompiled Minecraft sources. Dependencies vary by version;\n"
                        + "// add LWJGL/brigadier/etc. matching your target version to compile.\n",
                StandardCharsets.UTF_8);
    }
}
