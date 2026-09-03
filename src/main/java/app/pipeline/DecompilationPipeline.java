package app.pipeline;

import app.bytecode.BytecodeAnalyzer;
import app.bytecode.BytecodeClass;
import app.bytecode.ClassGraph;
import app.cache.CacheManager;
import app.core.DecompileException;
import app.core.DecompileOptions;
import app.core.DecompileProgressListener;
import app.core.DecompileStats;
import app.core.DecompiledProject;
import app.core.GameVersion;
import app.core.InputJar;
import app.core.VersionDetectionResult;
import app.core.VersionDetector;
import app.decompiler.DecompilerEngine;
import app.decompiler.DecompilerRegistry;
import app.decompiler.JavapFallbackEngine;
import app.mappings.MappingProvider;
import app.mappings.MappingResolver;
import app.mappings.MappingSet;
import app.project.ProjectGenerator;
import app.reconstruction.SourceProcessor;
import app.util.HashUtils;
import app.util.JarUtils;
import app.versions.VersionProfile;
import app.versions.VersionRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Single shared core pipeline used by GUI, CLI and tests alike
 * (spec: "GUI must use the same core API as CLI — no two implementations").
 *
 * <pre>
 * JAR -&gt; Version Detection -&gt; Profile -&gt; Mappings -&gt; Bytecode -&gt;
 * Decompiler -&gt; Remap -&gt; Reconstruction -&gt; Project Generator
 * </pre>
 */
public final class DecompilationPipeline {

    private final VersionRegistry versions = new VersionRegistry();
    private final DecompilerRegistry decompilers = new DecompilerRegistry();
    private final CacheManager cache = new CacheManager();

    public VersionRegistry versions() {
        return versions;
    }

    public DecompilerRegistry decompilers() {
        return decompilers;
    }

    public CacheManager cache() {
        return cache;
    }

    public DecompiledProject run(DecompileOptions options, DecompileProgressListener listener) throws Exception {
        DecompileStats stats = new DecompileStats();
        Path input = options.getInputJar().toAbsolutePath();
        Path output = options.getOutputDirectory().toAbsolutePath();

        if (!Files.isRegularFile(input)) {
            throw new DecompileException("Input JAR not found: " + input);
        }
        if (listener.isCancelled()) {
            throw new DecompileException("Cancelled before start.");
        }

        // Fingerprint.
        listener.onStatus("Hashing input JAR...");
        String sha256;
        try {
            sha256 = HashUtils.sha256(input);
        } catch (IOException e) {
            throw new DecompileException("Cannot hash input JAR: " + e.getMessage(), e);
        }
        InputJar inputJar = new InputJar(input, sha256, Files.size(input));

        // Output dir: create, warn if non-empty (never delete user files).
        Files.createDirectories(output);
        try (var s = Files.list(output)) {
            if (s.findAny().isPresent()) {
                String warn = "Output directory is not empty; existing files may be overwritten: " + output;
                stats.addWarning(warn);
                listener.onWarning(warn);
            }
        }

        // 1. Version detection.
        listener.onStatus("Detecting Minecraft version...");
        VersionDetectionResult detection = VersionDetector.detect(input, options.getVersionOverride());
        GameVersion version = detection.getBest();
        listener.onStatus("Detected version: " + version + " (confidence "
                + Math.round(detection.getConfidence() * 100) + "%)");
        for (String e : detection.getEvidence()) {
            listener.onStatus("  evidence: " + e);
        }
        if (!detection.isConfident()) {
            listener.onWarning("Could not determine Minecraft version confidently. Candidates: "
                    + detection.getCandidates() + ". Continuing best-effort; use --version to override.");
        }

        // 2. Profile.
        VersionProfile profile = versions.select(version);
        listener.onStatus("Using profile: " + profile.label() + " [" + profile.id() + "]");
        if (!profile.notes().isEmpty()) {
            listener.onStatus("  " + profile.notes());
        }

        // 3. Mappings: explicit path, then local auto-find, then auto-download.
        // Offline-first but not offline-only: downloads are opt-out (--offline).
        listener.onStatus("Resolving mappings (" + profile.mappingsFormat() + ")...");
        MappingProvider provider = MappingResolver.resolve(version,
                options.getCustomMappingsPath(), output, input, options.getMappingsLibraryDir());
        if (provider instanceof MappingResolver.AutoFoundProvider auto) {
            listener.onStatus("Auto-found mappings: " + auto.candidate().path()
                    + " [" + auto.candidate().format() + "]");
        }
        MappingSet mappings;
        try {
            mappings = provider.loadMappings(version);
        } catch (Exception e) {
            stats.addWarning("Mapping load failed (" + e.getMessage() + "); continuing without mappings.");
            listener.onWarning("Mapping load failed: " + e.getMessage());
            mappings = new MappingSet();
        }
        if (mappings.isEmpty() && options.isAutoDownloadMappings() && !listener.isCancelled()) {
            listener.onStatus("No local mappings - trying automatic download ...");
            java.nio.file.Path downloaded = new app.mappings.download.MappingDownloader()
                    .fetchBest(version, app.cache.CachePaths.mappingsCache(), listener);
            if (downloaded != null) {
                try {
                    MappingSet fetched = new app.mappings.FileMappingProvider(downloaded)
                            .loadMappings(version);
                    if (!fetched.isEmpty()) {
                        fetched.putMetadata("provider", "download");
                        fetched.putMetadata("downloadedFrom", downloaded.toString());
                        mappings = fetched;
                    } else {
                        listener.onWarning("Downloaded mappings came back empty; continuing without them.");
                    }
                } catch (Exception e) {
                    stats.addWarning("Downloaded mappings unreadable (" + e.getMessage() + ").");
                    listener.onWarning("Downloaded mappings unreadable: " + e.getMessage());
                }
            } else {
                listener.onStatus("No downloadable mappings for this version; synthetic names will be used.");
            }
        }
        listener.onStatus("Mappings: " + (mappings.isEmpty() ? "none - synthetic names will be used"
                : mappings.size() + " entries via " + mappings.getMetadata().getOrDefault("provider", "?")));

        // 4. Bytecode analysis (streaming pass for stats + ClassGraph).
        listener.onStatus("Analyzing bytecode...");
        ClassGraph graph = new ClassGraph();
        Map<String, BytecodeClass> analyzed;
        try {
            analyzed = BytecodeAnalyzer.analyzeJar(input);
        } catch (IOException e) {
            throw new DecompileException("Cannot read input JAR: " + e.getMessage(), e);
        }
        graph.addAll(analyzed.values());
        stats.incAnalyzed(analyzed.size());
        listener.onStatus("Classes analyzed: " + analyzed.size());
        copyResources(input, output.resolve("resources"), listener, stats);

        // 5. Decompile (with per-class fallback so one bad class never kills the run).
        DecompilerEngine engine = options.getDecompilerName().equalsIgnoreCase("auto")
                ? decompilers.auto(version)
                : decompilers.byName(options.getDecompilerName());
        // Profile preference: if user picked auto but profile prefers non-default, honor profile.
        listener.onStatus("Decompiling with " + engine.label() + " (" + analyzed.size() + " classes)...");
        Map<String, String> raw;
        try {
            raw = engine.decompile(input, version, options, stats, listener, profile.decompilerOptions());
        } catch (Exception e) {
            stats.addWarning("Primary decompiler failed (" + e.getMessage() + "); trying javap fallback.");
            listener.onWarning("Decompiler failed: " + e.getMessage());
            raw = new JavapFallbackEngine().decompile(input, version, options, stats, listener, Map.of());
        }
        if (listener.isCancelled()) {
            throw new DecompileException("Cancelled during decompilation.");
        }
        // Top-up: any analyzed class missing from decompiler output gets an ASM stub
        // so "no class is lost" (spec section 20).
        List<String> failed = new ArrayList<>();
        Map<String, String> complete = new LinkedHashMap<>(raw);
        for (Map.Entry<String, BytecodeClass> e : analyzed.entrySet()) {
            if (!complete.containsKey(e.getKey())) {
                failed.add(e.getKey());
                complete.put(e.getKey(), JavapFallbackEngine.stubFromBytecode(e.getValue(),
                        "Decompiler produced no output; structure preserved from bytecode."));
                stats.addError("No output for " + e.getKey() + " - fallback stub generated.");
            }
        }
        // Also keep any extra classes the decompiler found (e.g. package-info).
        listener.onStatus("Decompiled: " + raw.size() + ", fallbacks: " + failed.size());

        // 6-7. Post-processing + remap.
        listener.onStatus("Post-processing sources...");
        SourceProcessor.ProcessedSources processed =
                SourceProcessor.process(complete, mappings, profile, stats, listener);
        if (listener.isCancelled()) {
            throw new DecompileException("Cancelled during post-processing.");
        }

        // 8. Generate project tree.
        listener.onStatus("Writing project to " + output + "...");
        ProjectGenerator.GeneratedProject gen = ProjectGenerator.generate(output, version,
                profile.id(), engine.name(), processed.sources(), mappings, stats,
                failed, listener, input.getFileName().toString(), sha256);

        // 9. Cache (best-effort, never fails the run).
        if (options.isUseCache()) {
            try {
                Map<String, Object> meta = new HashMap<>();
                meta.put("version", version.getId());
                meta.put("era", version.getEra().name());
                meta.put("profile", profile.id());
                meta.put("decompiler", engine.name());
                meta.put("classes", processed.sources().size());
                cache.storeMetadata(sha256, meta);
                cache.storeSources(sha256, processed.sources(), listener);
            } catch (Exception e) {
                listener.onWarning("Cache store failed: " + e.getMessage());
            }
        }

        stats.finish();
        listener.onStatus("Done.\n" + stats.summary());
        return new DecompiledProject(output, gen.sourcesRoot(), version, profile.id(),
                engine.name(), stats, gen.classToSource(), failed);
    }

    /** Copies non-class resources (pack.mcmeta, lang, sounds.json, ...) into resources/. */
    private void copyResources(Path jar, Path resourcesRoot, DecompileProgressListener listener,
                               DecompileStats stats) {
        try {
            Files.createDirectories(resourcesRoot);
            int copied = 0;
            try (JarFile jf = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> en = jf.entries();
                while (en.hasMoreElements()) {
                    JarEntry e = en.nextElement();
                    if (e.isDirectory() || e.getName().endsWith(".class")
                            || e.getName().startsWith("META-INF/")) {
                        continue;
                    }
                    try {
                        JarUtils.copyEntryToFile(jar, e.getName(), resourcesRoot.resolve(e.getName()));
                        copied++;
                    } catch (Exception ex) {
                        stats.addWarning("Resource copy failed for " + e.getName() + ": " + ex.getMessage());
                    }
                    if (listener.isCancelled()) {
                        break;
                    }
                }
            }
            listener.onStatus("Resources copied: " + copied);
        } catch (Exception e) {
            listener.onWarning("Resource copy failed: " + e.getMessage());
        }
    }
}
