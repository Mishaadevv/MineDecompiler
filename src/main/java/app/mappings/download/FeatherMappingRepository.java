package app.mappings.download;

import app.core.DecompileProgressListener;
import app.core.GameVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OrnitheMC Feather mappings (CC0-1.0, community) for legacy versions —
 * classic/beta/alpha through 1.14.4, e.g. {@code a1.2.3_01}:
 * {@code feather-gen2-<mcversion>+build.N-tiny.gz} from the OrnitheMC maven.
 * Verified endpoint layout; resolution goes through {@code maven-metadata.xml}
 * so exact build numbers never need hard-coding.
 */
public final class FeatherMappingRepository implements MappingRepository {

    static final String MAVEN_BASE =
            "https://maven.ornithemc.net/releases/net/ornithemc/feather-gen2";

    @Override
    public String name() {
        return "feather";
    }

    @Override
    public boolean suitableFor(GameVersion version) {
        return switch (version.getEra()) {
            case ALPHA, BETA, LEGACY_RELEASE, UNKNOWN -> true;
            case MODERN_RELEASE -> isAtMost114(version.getId());
            case FUTURE -> false;
        };
    }

    @Override
    public Path fetch(GameVersion version, Path cacheRoot, DecompileProgressListener listener) throws Exception {
        String compact = compactId(version.getId());
        if (compact == null) {
            return null;
        }
        Path dir = cacheRoot.resolve("feather");
        Files.createDirectories(dir);
        Path metadata = dir.resolve("maven-metadata.xml");
        if (!DownloadUtil.isFresh(metadata, 7L * 24 * 3600 * 1000)) {
            DownloadUtil.download(MAVEN_BASE + "/maven-metadata.xml", metadata,
                    null, 0, listener, "Feather version index");
        }
        String mappingVersion = pickMappingVersion(metadata, compact);
        if (mappingVersion == null) {
            listener.onStatus("Feather: no mappings for " + version.getId() + ".");
            return null;
        }
        String base = MAVEN_BASE + "/" + mappingVersion + "/feather-gen2-" + mappingVersion;
        Path dest = dir.resolve(compact + "-tiny.gz");
        String sha1 = null;
        try {
            String shaText = DownloadUtil.getText(base + "-tiny.gz.sha1", listener).trim();
            sha1 = shaText.split("\\s+")[0];
        } catch (Exception e) {
            listener.onWarning("Feather checksum unavailable, downloading unverified.");
        }
        DownloadUtil.download(base + "-tiny.gz", dest, sha1, 0, listener,
                "Feather mappings for " + compact + " (CC0)");
        return Files.isRegularFile(dest) && Files.size(dest) > 0 ? dest : null;
    }

    /**
     * Normalizes a detected id to OrnitheMC version form:
     * {@code "alpha 1.2.3_01"} → {@code "a1.2.3_01"},
     * {@code "1.20.1"} → {@code "1.20.1"}, unknown → {@code null}.
     */
    static String compactId(String id) {
        if (id == null) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT).trim();
        if (lower.startsWith("unknown") || lower.isEmpty()) {
            return null;
        }
        if (lower.startsWith("alpha ")) {
            return "a" + lower.substring(6).replaceAll("\\s+", "");
        }
        if (lower.startsWith("beta ")) {
            return "b" + lower.substring(5).replaceAll("\\s+", "");
        }
        // Bare "1.2.3" could be release; keep as-is (metadata match decides).
        return lower.replaceAll("\\s+", "");
    }

    /** Picks the mapping version: exact {@code <compact>+build.N} (newest N), else boundary-safe prefix. */
    static String pickMappingVersion(Path metadataFile, String compact) throws Exception {
        String xml = Files.readString(metadataFile);
        List<String> versions = new ArrayList<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) {
            versions.add(m.group(1).trim());
        }
        String bestBuild = null;
        for (String v : versions) {
            if (v.startsWith(compact + "+build.")) {
                bestBuild = v; // metadata is chronological: last = newest
            }
        }
        if (bestBuild != null) {
            return bestBuild;
        }
        for (String v : versions) {
            if (v.startsWith(compact)
                    && (v.length() == compact.length()
                    || "+_-".indexOf(v.charAt(compact.length())) >= 0)) {
                return v;
            }
        }
        return null;
    }

    private static boolean isAtMost114(String id) {
        Matcher m = Pattern.compile("\\b1\\.(\\d+)").matcher(id);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1)) <= 14;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }
}
