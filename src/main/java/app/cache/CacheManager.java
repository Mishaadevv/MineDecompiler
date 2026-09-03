package app.cache;

import app.core.DecompileProgressListener;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Disk cache keyed by input JAR SHA-256 (spec section 16):
 * {@code ~/.minecraft-decompiler/cache/<hash>/}.
 * Caches mappings + bytecode summaries + per-class sources so re-runs and
 * the "re-process project" action are incremental.
 */
public final class CacheManager {

    private final Path cacheRoot;

    public CacheManager() {
        this(defaultRoot());
    }

    public CacheManager(Path cacheRoot) {
        this.cacheRoot = cacheRoot;
    }

    public static Path defaultRoot() {
        return CachePaths.runCache();
    }

    public Path dirFor(String sha256) {
        return cacheRoot.resolve(sha256 == null || sha256.isEmpty() ? "unknown" : sha256);
    }

    public boolean has(String sha256) {
        if (sha256 == null || sha256.isEmpty()) {
            return false;
        }
        return Files.isRegularFile(dirFor(sha256).resolve("metadata.json"));
    }

    public void storeMetadata(String sha256, Map<String, Object> metadata) throws IOException {
        Path dir = dirFor(sha256);
        Files.createDirectories(dir);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Files.writeString(dir.resolve("metadata.json"), gson.toJson(metadata), StandardCharsets.UTF_8);
    }

    public Map<String, Object> loadMetadata(String sha256) throws IOException {
        Path f = dirFor(sha256).resolve("metadata.json");
        if (!Files.isRegularFile(f)) {
            return Map.of();
        }
        Gson gson = new Gson();
        String json = Files.readString(f, StandardCharsets.UTF_8);
        Map<?, ?> raw = gson.fromJson(json, Map.class);
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }

    /** Copies previously decompiled sources from cache into a map (best-effort). */
    public Map<String, String> loadCachedSources(String sha256, DecompileProgressListener listener) {
        Map<String, String> out = new LinkedHashMap<>();
        Path srcDir = dirFor(sha256).resolve("sources");
        if (!Files.isDirectory(srcDir)) {
            return out;
        }
        try (var s = Files.walk(srcDir)) {
            for (Path p : (Iterable<Path>) s.filter(q -> q.toString().endsWith(".java"))::iterator) {
                try {
                    String rel = srcDir.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                    out.put(rel.substring(0, rel.length() - 5),
                            Files.readString(p, StandardCharsets.UTF_8));
                } catch (Exception e) {
                    listener.onWarning("Cache read failed for " + p + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            listener.onWarning("Cache scan failed: " + e.getMessage());
        }
        return out;
    }

    /** Persists decompiled sources into the cache (streaming, bounded memory). */
    public void storeSources(String sha256, Map<String, String> sources,
                             DecompileProgressListener listener) {
        Path srcDir = dirFor(sha256).resolve("sources");
        try {
            Files.createDirectories(srcDir);
            for (Map.Entry<String, String> e : sources.entrySet()) {
                Path dest = srcDir.resolve(e.getKey() + ".java");
                try {
                    Files.createDirectories(dest.getParent());
                    Files.writeString(dest, e.getValue(), StandardCharsets.UTF_8);
                } catch (Exception ex) {
                    listener.onWarning("Cache write failed for " + e.getKey() + ": " + ex.getMessage());
                }
                if (listener.isCancelled()) {
                    break;
                }
            }
        } catch (IOException e) {
            listener.onWarning("Cache store failed: " + e.getMessage());
        }
    }

    public void clear(String sha256) throws IOException {
        Path dir = dirFor(sha256);
        if (Files.isDirectory(dir)) {
            try (var s = Files.walk(dir)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }
}
