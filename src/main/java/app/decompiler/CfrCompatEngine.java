package app.decompiler;

import app.core.DecompileOptions;
import app.core.DecompileProgressListener;
import app.core.DecompileStats;
import app.core.GameVersion;

import java.nio.file.Path;
import java.util.Map;

/**
 * Compatibility shim: prefers CFR when {@code org.benf.cfr.reader.Main} is on
 * the classpath (user-supplied), otherwise delegates to Vineflower/javap.
 * This keeps CFR support pluggable without a hard dependency.
 */
public final class CfrCompatEngine implements DecompilerEngine {

    private final VineflowerEngine vineflower = new VineflowerEngine();
    private final JavapFallbackEngine javap = new JavapFallbackEngine();

    @Override
    public String name() {
        return "cfr";
    }

    @Override
    public String label() {
        return "CFR (compat)";
    }

    @Override
    public boolean isAvailable() {
        return true; // always resolvable via delegation
    }

    public static boolean isCfrPresent() {
        try {
            Class.forName("org.benf.cfr.reader.Main");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public Map<String, String> decompile(Path jar, GameVersion version, DecompileOptions options,
                                         DecompileStats stats, DecompileProgressListener listener,
                                         Map<String, String> extraOptions) throws Exception {
        if (isCfrPresent()) {
            listener.onStatus("CFR detected on classpath - delegating via CFR Main (compat mode).");
            try {
                return runCfrViaMain(jar, version, options, stats, listener);
            } catch (Exception e) {
                stats.addWarning("CFR compat run failed, falling back: " + e.getMessage());
                listener.onWarning("CFR failed (" + e.getMessage() + "), using Vineflower.");
            }
        } else {
            listener.onStatus("CFR not on classpath - using Vineflower (CFR-compat mode).");
        }
        if (vineflower.isAvailable()) {
            return vineflower.decompile(jar, version, options, stats, listener, extraOptions);
        }
        return javap.decompile(jar, version, options, stats, listener, extraOptions);
    }

    private Map<String, String> runCfrViaMain(Path jar, GameVersion version, DecompileOptions options,
                                              DecompileStats stats, DecompileProgressListener listener) throws Exception {
        // Invoke CFR reflectively: Main.main(new String[]{jar, "--outputdir", tmp})
        // then read back .java files. Fully version-tolerant.
        Path tmp = java.nio.file.Files.createTempDirectory("mcdecompiler-cfr-");
        try {
            Class<?> main = Class.forName("org.benf.cfr.reader.Main");
            var m = main.getMethod("main", String[].class);
            m.invoke(null, (Object) new String[]{jar.toAbsolutePath().toString(),
                    "--outputdir", tmp.toAbsolutePath().toString()});
            Map<String, String> out = new java.util.LinkedHashMap<>();
            try (var s = java.nio.file.Files.walk(tmp)) {
                for (Path p : (Iterable<Path>) s.filter(q -> q.toString().endsWith(".java"))::iterator) {
                    String rel = tmp.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                    out.put(rel.substring(0, rel.length() - 5),
                            java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8));
                    stats.incDecompiled();
                }
            }
            return out;
        } finally {
            try (var s = java.nio.file.Files.walk(tmp)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception ignored) {
            }
        }
    }
}
