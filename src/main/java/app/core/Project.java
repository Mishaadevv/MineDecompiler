package app.core;

import java.nio.file.Path;
import java.util.List;

/**
 * A lightweight in-memory handle to an opened (already decompiled) project
 * directory, used by the GUI viewer without re-running the pipeline.
 */
public final class Project {

    private final Path root;
    private final Path sourcesRoot;
    private final GameVersion version;
    private final List<String> classes;

    public Project(Path root, Path sourcesRoot, GameVersion version, List<String> classes) {
        this.root = root;
        this.sourcesRoot = sourcesRoot;
        this.version = version;
        this.classes = List.copyOf(classes);
    }

    public Path getRoot() {
        return root;
    }

    public Path getSourcesRoot() {
        return sourcesRoot;
    }

    public GameVersion getVersion() {
        return version;
    }

    public List<String> getClasses() {
        return classes;
    }
}
