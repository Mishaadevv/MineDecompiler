package app.mappings;

import app.core.GameVersion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Automatic local mappings discovery (the "Find Mappings" feature).
 * Works exactly like version detection: it gathers evidence from every
 * local source and ranks candidates, but it never touches the network.
 *
 * <p>Sources scanned (shallow and bounded, so even huge disks stay fast):
 * <ol>
 *   <li>{@code <output>/mappings/} — conventional drop zone,</li>
 *   <li>{@code ./mappings/} — working-directory drop zone,</li>
 *   <li>the input JAR's own folder (sibling {@code *.tiny}/{@code *.srg}/
 *       {@code client.txt} files people keep next to their jars),</li>
 *   <li>a user-configured mappings library folder (Settings / {@code --mappings-lib}),
 *       searched up to 3 levels deep with a file-visit cap.</li>
 * </ol>
 *
 * <p>Recognized formats: Mojang ProGuard ({@code client.txt}/{@code server.txt}),
 * Tiny v1/v2 ({@code *.tiny}), SRG/TSRG ({@code *.srg}), {@code *.properties}.
 */
public final class MappingFinder {

    /** One ranked discovery result. */
    public record Candidate(Path path, boolean directory, String format, String note, int score,
                            Origin origin) {
        public String displayName() {
            String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
            return name + "  [" + format + "]  " + note + "  (score " + score + ")";
        }
    }

    /** Where a candidate came from — explicit user places outrank the implicit cache. */
    public enum Origin {
        DROPZONE, LIBRARY, JAR_SIBLING, CACHE
    }

    /** Score at/above which the GUI auto-fills the field without asking. */
    public static final int AUTO_FILL_THRESHOLD = 60;
    private static final int MAX_RESULTS = 20;
    private static final int MAX_LIBRARY_FILES = 800;

    private MappingFinder() {
    }

    /**
     * Searches all local sources and returns candidates sorted best-first.
     * Any argument may be null (that source is then skipped). Never throws.
     */
    public static List<Candidate> search(GameVersion version, Path inputJar,
                                         Path outputDir, Path libraryDir) {
        List<Found> files = new ArrayList<>();
        // 1. Output drop zone (files only).
        collectFiles(outputDir == null ? null : outputDir.resolve("mappings"), 1,
                Origin.DROPZONE, files);
        // 2. Working-directory drop zone (files only).
        collectFiles(Path.of("mappings"), 1, Origin.DROPZONE, files);
        // 3. Siblings of the input jar (files only, never recursive).
        if (inputJar != null && inputJar.getParent() != null) {
            collectFiles(inputJar.getParent(), 1, Origin.JAR_SIBLING, files);
        }
        // 4. User library (bounded recursive walk).
        collectFiles(libraryDir, 3, Origin.LIBRARY, files);
        // 5. Shared download cache (~/.minecraft-decompiler/mappings): packs
        //    fetched once work offline forever.
        try {
            collectFiles(app.cache.CachePaths.mappingsCache(), 3, Origin.CACHE, files);
        } catch (Exception ignored) {
        }

        List<Candidate> out = new ArrayList<>();
        for (Found f : files) {
            try {
                Candidate c = inspect(f.path, f.origin, version);
                if (c != null) {
                    out.add(c);
                }
            } catch (Exception ignored) {
            }
        }
        // Directory candidates: a folder holding 2+ mapping files is usable as-is.
        out.addAll(directoryCandidates(files, version));
        out.sort(Comparator.comparingInt(Candidate::score).reversed()
                .thenComparing(c -> c.origin().ordinal())
                .thenComparing(c -> c.directory() ? 1 : 0) // precise files before folders
                .thenComparing(c -> c.path().toString()));
        if (out.size() > MAX_RESULTS) {
            return out.subList(0, MAX_RESULTS);
        }
        return out;
    }

    /** Best candidate, if any. */
    public static Optional<Candidate> bestFor(GameVersion version, Path inputJar,
                                              Path outputDir, Path libraryDir) {
        List<Candidate> all = search(version, inputJar, outputDir, libraryDir);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /**
     * True when the directory holds at least one sniff-parseable mapping file.
     * Used for explicit drop zones ({@code <output>/mappings/}), where user
     * intent outranks heuristic scores.
     */
    public static boolean hasUsableMappings(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                if (sniffFormat(p) != null) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------- scanning

    private static void collectFiles(Path root, int maxDepth, Origin origin, List<Found> out) {
        if (root == null || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> s = Files.walk(root, maxDepth)) {
            for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                if (out.size() >= MAX_LIBRARY_FILES) {
                    break;
                }
                if (looksMappableByName(p)) {
                    out.add(new Found(p, origin));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private record Found(Path path, Origin origin) {
    }

    private static boolean looksMappableByName(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".tiny") || n.endsWith(".tiny.gz") || n.endsWith(".gz")
                || n.contains("tiny") || n.endsWith(".srg") || n.endsWith(".tsrg")
                || n.endsWith(".xsrg") || n.endsWith(".csrg")) {
            return true;
        }
        if (n.endsWith(".txt")) {
            // Conventional Mojang names, with or without version: client.txt,
            // server-1.20.1.txt, 1.20.1-client_mappings.txt, ...
            String stem = n.substring(0, n.length() - 4);
            if (stem.startsWith("client") || stem.startsWith("server")
                    || stem.endsWith("client") || stem.endsWith("server")
                    || stem.endsWith("mappings") || looksMappableTxt(n)) {
                return true;
            }
        }
        return n.contains("mapping");
    }

    private static boolean looksMappableTxt(String lowerName) {
        return lowerName.contains("mojang") || lowerName.contains("proguard")
                || lowerName.contains("mcp") || lowerName.contains("srg")
                || lowerName.contains("tiny") || lowerName.contains("minecraft");
    }

    // ------------------------------------------------------------- inspection

    private static Candidate inspect(Path f, Origin origin, GameVersion version) {
        String format = sniffFormat(f);
        if (format == null) {
            return null;
        }
        int score = score(f, format, version);
        return new Candidate(f.toAbsolutePath(), false, format,
                describeSource(f) + ", " + countHint(f), score, origin);
    }

    /** Cheap format sniff (reads only the file head). */
    static String sniffFormat(Path f) {
        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
        if (TinyMappingProvider.looksLikeTiny(f)) {
            return "tiny";
        }
        if (SrgMappingProvider.looksLikeSrg(f)) {
            return "srg/tsrg";
        }
        if (MojangMappingProvider.looksLikeMojang(f)) {
            return "mojang";
        }
        if (n.endsWith(".properties")) {
            return "properties";
        }
        return null;
    }

    private static int score(Path f, String format, GameVersion version) {
        int score = 0;
        String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
        String full = f.toAbsolutePath().toString().toLowerCase(Locale.ROOT);

        // Format expected for this era?
        boolean modern = version != null && (version.getEra() == GameVersion.Era.MODERN_RELEASE
                || version.getEra() == GameVersion.Era.FUTURE);
        boolean legacy = version != null && (version.getEra() == GameVersion.Era.LEGACY_RELEASE
                || version.getEra() == GameVersion.Era.BETA
                || version.getEra() == GameVersion.Era.ALPHA);
        if (modern && format.equals("mojang")) {
            score += 30;
        } else if (legacy && (format.startsWith("srg") || format.equals("tiny"))) {
            score += 30;
        } else if (format.equals("tiny")) {
            score += 15; // tiny exists for every era (community mappings)
        } else {
            score += 10;
        }

        // Version id match (numeric token with boundaries, so 1.1 != 1.19).
        if (version != null && !version.getId().startsWith("unknown")) {
            String id = version.getId().toLowerCase(Locale.ROOT);
            boolean hit = false;
            Matcher tm = Pattern.compile("\\d+(?:\\.\\d+)+").matcher(id);
            while (tm.find()) {
                if (containsToken(full, tm.group())) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                score += 40;
            }
            // Era keywords: "beta 1.7.3" should match ".../beta/.../b1.7.3...".
            String eraWord = version.getEra() == GameVersion.Era.ALPHA ? "alpha"
                    : version.getEra() == GameVersion.Era.BETA ? "beta" : null;
            if (eraWord != null && (full.contains(eraWord)
                    || full.contains(eraWord.charAt(0) + versionNumberPart(id)))) {
                score += 20;
            }
        }

        // Conventional Mojang file names.
        if (name.equals("client.txt") || name.equals("server.txt")) {
            score += 15;
        }
        // Era guard: Mojang never published Alpha/Beta mappings, so a Mojang
        // file can never be right for those eras no matter what else matches.
        if (version != null && (version.getEra() == GameVersion.Era.ALPHA
                || version.getEra() == GameVersion.Era.BETA)
                && format.equals("mojang")) {
            score = Math.min(score, 5);
        }
        return score;
    }

    private static String versionNumberPart(String id) {
        Matcher m = Pattern.compile("\\d+(?:\\.\\d+)+").matcher(id);
        return m.find() ? m.group() : "";
    }

    private static boolean containsToken(String haystack, String token) {
        // Boundaries: "1.1" must not match "1.19" or "11.1", but "1.20.1"
        // must match "client-1.20.1.txt" (trailing ".txt" is fine).
        return Pattern.compile("(?<![\\d.])" + Pattern.quote(token) + "(?!\\.?\\d)")
                .matcher(haystack).find();
    }

    private static String describeSource(Path f) {
        Path abs = f.toAbsolutePath();
        int depth = abs.getNameCount();
        if (depth >= 2) {
            return ".." + java.io.File.separator + abs.getName(depth - 2)
                    + java.io.File.separator + abs.getFileName();
        }
        return abs.getFileName().toString();
    }

    private static String countHint(Path f) {
        try {
            long size = Files.size(f);
            if (size < 1024) {
                return size + " B";
            }
            return (size / 1024) + " KB";
        } catch (IOException e) {
            return "size unknown";
        }
    }

    private static List<Candidate> directoryCandidates(List<Found> files, GameVersion version) {
        java.util.Map<Path, List<Found>> byDir = new java.util.LinkedHashMap<>();
        for (Found f : files) {
            if (f.path.getParent() != null) {
                byDir.computeIfAbsent(f.path.getParent(), k -> new ArrayList<>()).add(f);
            }
        }
        List<Candidate> out = new ArrayList<>();
        for (var e : byDir.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            int best = 0;
            Origin origin = Origin.CACHE;
            for (Found f : e.getValue()) {
                String fmt = sniffFormat(f.path);
                if (fmt != null) {
                    int s = score(f.path, fmt, version);
                    if (s > best) {
                        best = s;
                        origin = f.origin;
                    }
                }
            }
            if (best > 0) {
                // No bonus: a folder never outranks its own best file
                // (files-first tie-break in the sort guarantees that).
                out.add(new Candidate(e.getKey().toAbsolutePath(), true, "mixed/dir",
                        e.getValue().size() + " mapping files", best, origin));
            }
        }
        return out;
    }
}
