package app.decompiler;

import app.core.DecompileOptions;
import app.core.DecompileProgressListener;
import app.core.DecompileStats;
import app.core.GameVersion;

import java.nio.file.Path;
import java.util.Map;

/**
 * Pluggable decompiler abstraction (spec section 4).
 * Implementations decompile the raw JAR into {@code Map<classInternalName, javaSource>}.
 * Per-class failures must be recorded in stats, never abort the whole run.
 */
public interface DecompilerEngine {

    /** Registry name: "vineflower", "javap", "cfr-compat", ... */
    String name();

    /** Human-readable label for the GUI combo box. */
    String label();

    /** Whether this engine is usable in the current runtime. */
    boolean isAvailable();

    /**
     * Decompiles classes from the input JAR.
     *
     * @param jar        input JAR path (read-only)
     * @param version    detected version (may influence options)
     * @param options    run options (threads, cancellation via listener)
     * @param stats      accumulator for errors/warnings
     * @param listener   progress/cancellation
     * @param extraOptions profile-supplied decompiler flags
     * @return map of internal class name -&gt; decompiled source text
     */
    Map<String, String> decompile(Path jar, GameVersion version, DecompileOptions options,
                                  DecompileStats stats, DecompileProgressListener listener,
                                  Map<String, String> extraOptions) throws Exception;
}
