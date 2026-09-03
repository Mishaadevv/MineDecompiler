package app.core;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Value object describing the input JAR. Never modified; the original
 * file is always treated as read-only (see section 26.4 of the spec).
 */
public final class InputJar {

    private final Path path;
    private final String sha256;
    private final long sizeBytes;

    public InputJar(Path path, String sha256, long sizeBytes) {
        this.path = Objects.requireNonNull(path, "path");
        this.sha256 = sha256 == null ? "" : sha256;
        this.sizeBytes = sizeBytes;
    }

    public Path getPath() {
        return path;
    }

    public String getSha256() {
        return sha256;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    @Override
    public String toString() {
        return path + " (sha256=" + (sha256.length() > 12 ? sha256.substring(0, 12) + "..." : sha256) + ")";
    }
}
