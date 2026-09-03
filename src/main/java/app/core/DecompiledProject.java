package app.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Result of a completed decompilation run: where sources were written,
 * which version/profile was used, per-file outcomes and statistics.
 */
public final class DecompiledProject {

    private final Path outputDirectory;
    private final Path sourcesRoot;
    private final GameVersion version;
    private final String profileName;
    private final String decompilerName;
    private final DecompileStats stats;
    private final Map<String, Path> classToSource;
    private final List<String> failedClasses;

    public DecompiledProject(Path outputDirectory, Path sourcesRoot, GameVersion version,
                             String profileName, String decompilerName,
                             DecompileStats stats, Map<String, Path> classToSource,
                             List<String> failedClasses) {
        this.outputDirectory = outputDirectory;
        this.sourcesRoot = sourcesRoot;
        this.version = version;
        this.profileName = profileName;
        this.decompilerName = decompilerName;
        this.stats = stats;
        this.classToSource = Map.copyOf(classToSource);
        this.failedClasses = List.copyOf(failedClasses);
    }

    public Path getOutputDirectory() {
        return outputDirectory;
    }

    public Path getSourcesRoot() {
        return sourcesRoot;
    }

    public GameVersion getVersion() {
        return version;
    }

    public String getProfileName() {
        return profileName;
    }

    public String getDecompilerName() {
        return decompilerName;
    }

    public DecompileStats getStats() {
        return stats;
    }

    public Map<String, Path> getClassToSource() {
        return classToSource;
    }

    public List<String> getFailedClasses() {
        return failedClasses;
    }
}
