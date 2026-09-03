package app.mappings.download;

import app.core.DecompileProgressListener;
import app.core.GameVersion;

import java.nio.file.Path;

/**
 * One network source of mappings (official or community). Implementations must
 * be side-effect-limited to their own cache subdirectory and must return
 * {@code null} (not throw) when they simply have nothing for a version —
 * throwing is reserved for real I/O failures, which the orchestrator logs.
 */
public interface MappingRepository {

    /** Stable id for logs/metadata, e.g. "mojang", "feather". */
    String name();

    /** Cheap pre-check: is it even worth querying this repo for the version? */
    boolean suitableFor(GameVersion version);

    /**
     * Downloads (or reuses cached) mappings for the version.
     *
     * @return local file or directory with usable mappings, or {@code null}
     */
    Path fetch(GameVersion version, Path cacheRoot, DecompileProgressListener listener) throws Exception;
}
