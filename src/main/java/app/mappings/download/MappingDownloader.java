package app.mappings.download;

import app.core.DecompileProgressListener;
import app.core.GameVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Tries mapping repositories in era-appropriate order and returns the first
 * usable local path (downloads are cached, so this is a one-time cost per
 * version). Never throws for "nothing available" — returns {@code null} and
 * the pipeline continues with synthetic names.
 */
public final class MappingDownloader {

    private final List<MappingRepository> repositories;

    public MappingDownloader() {
        this(List.of(new MojangMappingRepository(), new FeatherMappingRepository()));
    }

    public MappingDownloader(List<MappingRepository> repositories) {
        this.repositories = List.copyOf(repositories);
    }

    public Path fetchBest(GameVersion version, Path cacheRoot, DecompileProgressListener listener) {
        for (MappingRepository repo : orderedFor(version)) {
            if (listener.isCancelled()) {
                return null;
            }
            try {
                if (!repo.suitableFor(version)) {
                    continue;
                }
                listener.onStatus("Trying " + repo.name() + " mappings for " + version.getId() + " ...");
                Path got = repo.fetch(version, cacheRoot, listener);
                if (got != null && Files.exists(got)) {
                    listener.onStatus("Obtained " + repo.name() + " mappings: " + got);
                    return got;
                }
            } catch (Exception e) {
                listener.onWarning(repo.name() + " download failed: " + e.getMessage()
                        + " — continuing without it.");
            }
        }
        return null;
    }

    private List<MappingRepository> orderedFor(GameVersion version) {
        MappingRepository mojang = null;
        MappingRepository feather = null;
        for (MappingRepository r : repositories) {
            if (r instanceof MojangMappingRepository) {
                mojang = r;
            } else if (r instanceof FeatherMappingRepository) {
                feather = r;
            }
        }
        // Era-appropriate order; any custom repositories appended after.
        return switch (version.getEra()) {
            case MODERN_RELEASE, FUTURE ->
                    concat(mojang, feather);
            case LEGACY_RELEASE, UNKNOWN ->
                    concat(feather, mojang);
            case ALPHA, BETA ->
                    feather == null ? List.of() : List.of(feather);
        };
    }

    private List<MappingRepository> concat(MappingRepository a, MappingRepository b) {
        java.util.ArrayList<MappingRepository> out = new java.util.ArrayList<>();
        if (a != null) {
            out.add(a);
        }
        if (b != null) {
            out.add(b);
        }
        for (MappingRepository r : repositories) {
            if (r != a && r != b) {
                out.add(r);
            }
        }
        return out;
    }
}
