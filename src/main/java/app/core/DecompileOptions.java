package app.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for one decompilation run. The output directory is always
 * user-chosen (spec 26.9/26.10) — never hard-coded.
 */
public final class DecompileOptions {

    private final Path inputJar;
    private final Path outputDirectory;
    private final String decompilerName;
    private final Path customMappingsPath;
    private final Path mappingsLibraryDir;
    private final String versionOverride;
    private final int threads;
    private final boolean useCache;
    private final boolean autoDownloadMappings;
    private final boolean verbose;

    private DecompileOptions(Builder b) {
        this.inputJar = Objects.requireNonNull(b.inputJar, "inputJar");
        this.outputDirectory = Objects.requireNonNull(b.outputDirectory, "outputDirectory");
        this.decompilerName = b.decompilerName == null ? "auto" : b.decompilerName;
        this.customMappingsPath = b.customMappingsPath;
        this.mappingsLibraryDir = b.mappingsLibraryDir;
        this.versionOverride = b.versionOverride;
        this.threads = b.threads <= 0 ? Runtime.getRuntime().availableProcessors() : b.threads;
        this.useCache = b.useCache;
        this.autoDownloadMappings = b.autoDownloadMappings;
        this.verbose = b.verbose;
    }

    public Path getInputJar() {
        return inputJar;
    }

    public Path getOutputDirectory() {
        return outputDirectory;
    }

    public String getDecompilerName() {
        return decompilerName;
    }

    public Path getCustomMappingsPath() {
        return customMappingsPath;
    }

    /** Optional folder scanned by Find Mappings (may be null). */
    public Path getMappingsLibraryDir() {
        return mappingsLibraryDir;
    }

    public String getVersionOverride() {
        return versionOverride;
    }

    public int getThreads() {
        return threads;
    }

    public boolean isUseCache() {
        return useCache;
    }

    /** Whether missing mappings may be downloaded (default true; --offline disables). */
    public boolean isAutoDownloadMappings() {
        return autoDownloadMappings;
    }

    public boolean isVerbose() {
        return verbose;
    }

    public static Builder builder(Path inputJar, Path outputDirectory) {
        return new Builder(inputJar, outputDirectory);
    }

    public static final class Builder {
        private final Path inputJar;
        private final Path outputDirectory;
        private String decompilerName = "auto";
        private Path customMappingsPath;
        private Path mappingsLibraryDir;
        private String versionOverride;
        private int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        private boolean useCache = true;
        private boolean autoDownloadMappings = true;
        private boolean verbose;

        public Builder(Path inputJar, Path outputDirectory) {
            this.inputJar = inputJar;
            this.outputDirectory = outputDirectory;
        }

        public Builder decompiler(String name) {
            this.decompilerName = name;
            return this;
        }

        public Builder customMappings(Path p) {
            this.customMappingsPath = p;
            return this;
        }

        public Builder mappingsLibrary(Path p) {
            this.mappingsLibraryDir = p;
            return this;
        }

        public Builder versionOverride(String v) {
            this.versionOverride = v;
            return this;
        }

        public Builder threads(int n) {
            this.threads = n;
            return this;
        }

        public Builder useCache(boolean b) {
            this.useCache = b;
            return this;
        }

        public Builder autoDownloadMappings(boolean b) {
            this.autoDownloadMappings = b;
            return this;
        }

        public Builder verbose(boolean b) {
            this.verbose = b;
            return this;
        }

        public DecompileOptions build() {
            return new DecompileOptions(this);
        }
    }

    /**
     * Suggests a default output folder name for a version id, e.g.
     * "1.1" -&gt; "Minecraft-1.1-Decompiled".
     */
    public static String suggestFolderName(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            return "Minecraft-Decompiled";
        }
        String safe = versionId.trim().replaceAll("[\\\\/:*?\"<>|]", "-");
        if (safe.toLowerCase().startsWith("minecraft")) {
            return safe + "-Decompiled";
        }
        return "Minecraft-" + safe + "-Decompiled";
    }

    /**
     * Validates that output dir usage is safe. Returns warnings (non-empty dir etc.).
     */
    public static List<String> validateOutputDirectory(java.io.File dir) {
        if (dir == null) {
            return List.of("Output directory is null.");
        }
        List<String> warnings = new ArrayList<>();
        if (dir.exists() && dir.isFile()) {
            warnings.add("Selected output path is an existing file, not a directory.");
        } else if (dir.exists() && dir.isDirectory()) {
            String[] list = dir.list();
            if (list != null && list.length > 0) {
                warnings.add("The selected directory is not empty (" + list.length + " entries). Existing files may be overwritten.");
            }
        }
        return Collections.unmodifiableList(warnings);
    }
}
