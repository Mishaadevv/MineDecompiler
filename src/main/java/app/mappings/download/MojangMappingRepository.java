package app.mappings.download;

import app.core.DecompileProgressListener;
import app.core.GameVersion;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Official Mojang mappings (ProGuard {@code client.txt}/{@code server.txt}),
 * resolved exactly like every launcher does:
 * {@code version_manifest_v2.json} → per-version JSON →
 * {@code downloads.client_mappings}/{@code server_mappings}.
 * Covers modern releases that ship mappings (1.14+ line); anything older
 * simply yields {@code null} so the next repository is tried.
 */
public final class MojangMappingRepository implements MappingRepository {

    static final String MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    @Override
    public String name() {
        return "mojang";
    }

    @Override
    public boolean suitableFor(GameVersion version) {
        // Mojang publishes no mappings for alpha/beta era versions.
        return version.getEra() != GameVersion.Era.ALPHA
                && version.getEra() != GameVersion.Era.BETA;
    }

    @Override
    public Path fetch(GameVersion version, Path cacheRoot, DecompileProgressListener listener) throws Exception {
        Path dir = cacheRoot.resolve("mojang");
        Files.createDirectories(dir);
        Path manifest = dir.resolve("version_manifest_v2.json");
        if (!DownloadUtil.isFresh(manifest, 24L * 3600 * 1000)) {
            DownloadUtil.download(MANIFEST_URL, manifest, null, 0, listener,
                    "Mojang version manifest");
        }
        String packageUrl = findPackageUrl(manifest, version);
        if (packageUrl == null) {
            listener.onStatus("Mojang: no manifest entry for " + version.getId() + ".");
            return null;
        }
        String safeId = safe(version.getId());
        Path versionJson = dir.resolve("versions").resolve(safeId + ".json");
        DownloadUtil.download(packageUrl, versionJson, null, 0, listener,
                "Mojang version info for " + version.getId());
        JsonObject root = JsonParser.parseString(
                Files.readString(versionJson)).getAsJsonObject();
        JsonObject downloads = root.has("downloads") && root.get("downloads").isJsonObject()
                ? root.getAsJsonObject("downloads") : new JsonObject();

        Path outDir = dir.resolve(safeId);
        Files.createDirectories(outDir);
        boolean gotAny = false;
        gotAny |= fetchArtifact(downloads, "client_mappings", outDir.resolve("client.txt"), listener);
        gotAny |= fetchArtifact(downloads, "server_mappings", outDir.resolve("server.txt"), listener);
        if (!gotAny) {
            listener.onStatus("Mojang: version " + version.getId() + " ships no mappings.");
            return null;
        }
        return outDir;
    }

    private boolean fetchArtifact(JsonObject downloads, String key, Path dest,
                                  DecompileProgressListener listener) {
        try {
            if (!downloads.has(key) || !downloads.get(key).isJsonObject()) {
                return false;
            }
            JsonObject art = downloads.getAsJsonObject(key);
            String url = art.has("url") ? art.get("url").getAsString() : null;
            if (url == null || url.isEmpty()) {
                return false;
            }
            String sha1 = art.has("sha1") ? art.get("sha1").getAsString() : null;
            long size = art.has("size") ? art.get("size").getAsLong() : 0;
            DownloadUtil.download(url, dest, sha1, size, listener, "Mojang " + key);
            return Files.isRegularFile(dest) && Files.size(dest) > 0;
        } catch (Exception e) {
            listener.onWarning("Mojang " + key + " failed: " + e.getMessage());
            return false;
        }
    }

    /** Matches the manifest entry against the detected id (+ alpha/beta spellings). */
    static String findPackageUrl(Path manifestFile, GameVersion version) throws Exception {
        JsonObject root = JsonParser.parseString(
                Files.readString(manifestFile)).getAsJsonObject();
        List<String> ids = candidateIds(version.getId());
        for (var el : root.getAsJsonArray("versions")) {
            JsonObject v = el.getAsJsonObject();
            String id = v.has("id") ? v.get("id").getAsString() : "";
            for (String cand : ids) {
                if (id.equalsIgnoreCase(cand)) {
                    return v.has("url") ? v.get("url").getAsString() : null;
                }
            }
        }
        return null;
    }

    static List<String> candidateIds(String id) {
        List<String> out = new ArrayList<>();
        if (id == null) {
            return out;
        }
        out.add(id);
        String lower = id.toLowerCase(Locale.ROOT).trim();
        // "alpha 1.2.3_01" -> "a1.2.3_01", "beta 1.7.3" -> "b1.7.3".
        if (lower.startsWith("alpha ")) {
            out.add("a" + lower.substring(6).replace(" ", ""));
        } else if (lower.startsWith("beta ")) {
            out.add("b" + lower.substring(5).replace(" ", ""));
        }
        out.add(lower.replace(" ", ""));
        return out;
    }

    static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}
