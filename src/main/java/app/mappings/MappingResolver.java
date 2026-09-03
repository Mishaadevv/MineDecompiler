package app.mappings;

import app.core.GameVersion;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves which provider(s) to use for a run. Priority:
 * <ol>
 *   <li>explicit custom file/dir ({@code --mappings} / Mappings field),</li>
 *   <li>automatic {@link MappingFinder} hit (output drop zone, working dir,
 *       jar siblings, mappings library),</li>
 *   <li>empty (the pipeline then tries auto-download, else synthetic names).</li>
 * </ol>
 */
public final class MappingResolver {

    private MappingResolver() {
    }

    public static MappingProvider resolve(GameVersion version, Path customPath, Path outputDir) {
        return resolve(version, customPath, outputDir, null, null);
    }

    public static MappingProvider resolve(GameVersion version, Path customPath,
                                          Path outputDir, Path inputJar, Path libraryDir) {
        if (customPath != null && (Files.isRegularFile(customPath) || Files.isDirectory(customPath))) {
            return new CompositeMappingProvider(
                    new FileMappingProvider(customPath), new EmptyMappingProvider());
        }
        // Explicit drop zones = user intent: any parseable file inside wins,
        // no score threshold.
        if (outputDir != null && MappingFinder.hasUsableMappings(outputDir.resolve("mappings"))) {
            return new CompositeMappingProvider(
                    new FileMappingProvider(outputDir.resolve("mappings")), new EmptyMappingProvider());
        }
        if (MappingFinder.hasUsableMappings(Path.of("mappings"))) {
            return new CompositeMappingProvider(
                    new FileMappingProvider(Path.of("mappings")), new EmptyMappingProvider());
        }
        // Heuristic search (jar siblings, library, download cache): only a
        // strong version+format match is auto-applied, otherwise the pipeline
        // falls through to automatic download instead of misapplying a
        // wrong-version pack.
        try {
            var best = MappingFinder.bestFor(version, inputJar, outputDir, libraryDir);
            if (best.isPresent() && best.get().score() >= MappingFinder.AUTO_FILL_THRESHOLD
                    && !best.get().directory()) {
                MappingSet probe = new FileMappingProvider(best.get().path()).loadMappings(version);
                if (!probe.isEmpty()) {
                    CompositeMappingProvider auto = new CompositeMappingProvider(
                            new FileMappingProvider(best.get().path()), new EmptyMappingProvider());
                    return new AutoFoundProvider(auto, best.get());
                }
            }
        } catch (Exception ignored) {
        }
        return new EmptyMappingProvider();
    }

    /** Wrapper that records which path was auto-found (visible in metadata). */
    public static final class AutoFoundProvider implements MappingProvider {
        private final MappingProvider delegate;
        private final MappingFinder.Candidate candidate;

        AutoFoundProvider(MappingProvider delegate, MappingFinder.Candidate candidate) {
            this.delegate = delegate;
            this.candidate = candidate;
        }

        public MappingFinder.Candidate candidate() {
            return candidate;
        }

        @Override
        public String name() {
            return "auto";
        }

        @Override
        public MappingSet loadMappings(GameVersion version) throws Exception {
            MappingSet set = delegate.loadMappings(version);
            set.putMetadata("provider", "auto:" + candidate.format());
            set.putMetadata("autoFound", candidate.path().toString());
            return set;
        }
    }
}
