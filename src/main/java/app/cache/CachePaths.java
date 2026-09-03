package app.cache;

import java.nio.file.Path;

/** Well-known on-disk locations of the tool (all under the user home). */
public final class CachePaths {

    private CachePaths() {
    }

    /**
     * Base directory. Overridable for tests/portable installs via
     * {@code -Dmcdecompiler.home=...} or the {@code MCDECOMPILER_HOME} env var.
     */
    public static Path appDir() {
        String sys = System.getProperty("mcdecompiler.home");
        if (sys != null && !sys.isBlank()) {
            return Path.of(sys);
        }
        String env = System.getenv("MCDECOMPILER_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        return Path.of(System.getProperty("user.home", "."), ".minecraft-decompiler");
    }

    /** Disk cache of previous runs (keyed by JAR hash). */
    public static Path runCache() {
        return appDir().resolve("cache");
    }

    /**
     * Default mappings library: auto-downloaded packs land here
     * ({@code mojang/<version>/}, {@code feather/<version>/}) and the
     * {@code Find Mappings} scan always includes it, so downloads work
     * offline forever after the first fetch.
     */
    public static Path mappingsCache() {
        return appDir().resolve("mappings");
    }
}
