package app.util;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers JVM installations present on this computer (Windows-first,
 * with Unix fallbacks) and selects a suitable one for a required version.
 *
 * <p>Sources consulted, in order: {@code JAVA_HOME}, {@code where}/{@code which}
 * results (i.e. {@code PATH}), well-known vendor install roots. Each candidate
 * is verified by actually running {@code java -version}, so stale registry
 * entries or broken links never poison the result.
 */
public final class JavaDiscovery {

    /** Minimum major Java version this application itself needs. */
    public static final int REQUIRED_MAJOR = 17;

    /** Description of one verified JVM installation. */
    public record JvmInfo(Path javaExe, Path home, int major,
                          String versionText, boolean hasJavac, boolean hasJavap) {
        public String shortName() {
            return "Java " + major + " (" + home + ")";
        }
    }

    private JavaDiscovery() {
    }

    public static int currentMajor() {
        return Runtime.version().feature();
    }

    public static Path currentJavaExe() {
        return Path.of(System.getProperty("java.home"), "bin", exeName("java"));
    }

    private static String exeName(String base) {
        return isWindows() ? base + ".exe" : base;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Full discovery; never throws (returns empty list on total failure). */
    public static List<JvmInfo> discover() {
        Map<String, Path> candidates = new LinkedHashMap<>();
        addJavaHome(candidates);
        addFromPathLookup(candidates);
        addWellKnownRoots(candidates);
        List<JvmInfo> out = new ArrayList<>();
        for (Path exe : candidates.values()) {
            try {
                JvmInfo info = inspect(exe);
                if (info != null) {
                    out.add(info);
                }
            } catch (Exception ignored) {
            }
        }
        out.sort(Comparator.comparingInt(JvmInfo::major).thenComparing(i -> i.home().toString()));
        return out;
    }

    /** Best JVM with {@code major >= minMajor} (lowest sufficient version wins). */
    public static Optional<JvmInfo> bestFor(int minMajor) {
        return discover().stream().filter(j -> j.major() >= minMajor).findFirst();
    }

    /** Verifies a single {@code java} executable by running {@code -version}. */
    public static JvmInfo inspect(Path javaExe) {
        if (javaExe == null || !Files.isRegularFile(javaExe)) {
            return null;
        }
        String out = queryVersion(javaExe);
        if (out == null) {
            return null;
        }
        int major = parseMajor(out);
        if (major <= 0) {
            return null;
        }
        Path home = javaExe.toAbsolutePath().getParent();
        if (home != null && home.getFileName() != null
                && home.getFileName().toString().equalsIgnoreCase("bin")) {
            home = home.getParent();
        }
        Path bin = home == null ? null : home.resolve("bin");
        boolean javac = bin != null && Files.isRegularFile(bin.resolve(exeName("javac")));
        boolean javap = bin != null && Files.isRegularFile(bin.resolve(exeName("javap")));
        return new JvmInfo(javaExe.toAbsolutePath(), home == null ? javaExe : home.toAbsolutePath(),
                major, firstLine(out), javac, javap);
    }

    /** Runs {@code java -version} (prints to stderr) and returns combined output. */
    public static String queryVersion(Path javaExe) {
        try {
            Process p = new ProcessBuilder(javaExe.toString(), "-version")
                    .redirectErrorStream(true).start();
            byte[] bytes = p.getInputStream().readAllBytes();
            boolean done = p.waitFor(15, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return null;
            }
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            return text.isBlank() ? null : text;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Parses the major version from {@code java -version} output.
     * Handles {@code "1.8.0_202"} (→ 8), {@code "11.0.2"}, {@code "17.0.17"},
     * {@code "21-ea"} etc. Returns -1 when unrecognized.
     */
    public static int parseMajor(String versionOutput) {
        if (versionOutput == null) {
            return -1;
        }
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(versionOutput);
        String v = m.find() ? m.group(1) : versionOutput.trim();
        if (v.startsWith("1.")) {
            // Legacy scheme: 1.8.x → 8.
            String[] parts = v.split("[.\\-_]");
            if (parts.length >= 2) {
                try {
                    return Integer.parseInt(parts[1]);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
            return -1;
        }
        Matcher n = Pattern.compile("(\\d+)").matcher(v);
        if (n.find()) {
            try {
                return Integer.parseInt(n.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------- sources

    private static void addJavaHome(Map<String, Path> out) {
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank()) {
            putCandidate(out, Path.of(jh.trim(), "bin", exeName("java")));
        }
    }

    private static void addFromPathLookup(Map<String, Path> out) {
        try {
            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("where", "java")
                    : new ProcessBuilder("which", "-a", "java");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String text = new String(p.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            p.waitFor(15, TimeUnit.SECONDS);
            for (String line : text.split("\\R")) {
                line = line.trim();
                if (!line.isEmpty() && !line.toLowerCase(Locale.ROOT).contains("not found")) {
                    putCandidate(out, Path.of(line));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void addWellKnownRoots(Map<String, Path> out) {
        List<Path> roots = new ArrayList<>();
        if (isWindows()) {
            roots.add(Path.of("C:\\Program Files\\Java"));
            roots.add(Path.of("C:\\Program Files (x86)\\Java"));
            roots.add(Path.of("C:\\Program Files\\Eclipse Adoptium"));
            roots.add(Path.of("C:\\Program Files\\Eclipse Foundation"));
            roots.add(Path.of("C:\\Program Files\\Amazon Corretto"));
            roots.add(Path.of("C:\\Program Files\\Microsoft"));
            roots.add(Path.of("C:\\Program Files\\Zulu"));
            roots.add(Path.of("C:\\Program Files\\BellSoft"));
            roots.add(Path.of("C:\\Program Files\\Semeru"));
            roots.add(Path.of("C:\\Java"));
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) {
                roots.add(Path.of(local, "Programs\\Eclipse Adoptium"));
            }
        } else {
            roots.add(Path.of("/usr/lib/jvm"));
            roots.add(Path.of("/opt"));
            String sdkman = System.getenv("SDKMAN_CANDIDATES_DIR");
            if (sdkman != null) {
                roots.add(Path.of(sdkman, "java"));
            }
            String home = System.getProperty("user.home");
            if (home != null) {
                roots.add(Path.of(home, ".sdkman/candidates/java"));
            }
        }
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path child : ds) {
                    putCandidate(out, child.resolve("bin").resolve(exeName("java")));
                    // Some distributions nest one level deeper (e.g. jdk-17/.../Contents/Home).
                    if (Files.isDirectory(child)) {
                        try (DirectoryStream<Path> inner = Files.newDirectoryStream(child)) {
                            for (Path g : inner) {
                                putCandidate(out, g.resolve("bin").resolve(exeName("java")));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void putCandidate(Map<String, Path> out, Path exe) {
        try {
            if (exe != null && Files.isRegularFile(exe)) {
                Path real = exe.toRealPath();
                Path home = real.getParent() != null && "bin".equalsIgnoreCase(
                        String.valueOf(real.getParent().getFileName()))
                        ? real.getParent().getParent() : real.getParent();
                out.putIfAbsent(home == null ? real.toString() : home.toString(), real);
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------- helpers

    private static volatile String javapCache;

    /**
     * Command used to run {@code javap}: current runtime's tool if present,
     * else the best discovered JDK that ships one, else plain {@code "javap"}
     * (PATH lookup by the OS). Never null.
     */
    public static synchronized String javapCommand() {
        if (javapCache != null) {
            return javapCache;
        }
        Path current = Path.of(System.getProperty("java.home"), "bin", exeName("javap"));
        if (Files.isRegularFile(current)) {
            return javapCache = current.toString();
        }
        String best = discover().stream()
                .filter(JvmInfo::hasJavap)
                .sorted(Comparator.comparingInt(JvmInfo::major).reversed())
                .map(j -> j.home().resolve("bin").resolve(exeName("javap")).toString())
                .findFirst().orElse(exeName("javap"));
        return javapCache = best;
    }

    /** Human-readable summary for logs, error dialogs and Settings. */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Running on Java ").append(currentMajor())
                .append(" (").append(System.getProperty("java.home")).append(")\n");
        List<JvmInfo> all = discover();
        if (all.isEmpty()) {
            sb.append("No other Java installations detected.\n");
        } else {
            sb.append("Detected Java installations:\n");
            for (JvmInfo j : all) {
                sb.append("  - Java ").append(j.major()).append(" at ").append(j.home());
                if (j.hasJavac()) {
                    sb.append(" [JDK]");
                }
                if (j.javaExe().equals(currentJavaExe())) {
                    sb.append(" [in use]");
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return (nl < 0 ? s : s.substring(0, nl)).trim();
    }
}
