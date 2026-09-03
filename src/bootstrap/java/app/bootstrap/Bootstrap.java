package app.bootstrap;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bootstrap entry point of the fat jar (declared as {@code Main-Class}).
 *
 * <p><b>Deliberately written in Java 8 source level</b> (no records, no
 * {@code var}, no {@code List.of}, no {@code Runtime.version()}): this class
 * must load even on an ancient default JVM. Its only job is to make sure the
 * real application starts on Java 17+:
 *
 * <ol>
 *   <li>If the current runtime is already 17+, reflectively calls the real
 *       CLI main ({@code app.cli.MainCli}) and gets out of the way.</li>
 *   <li>Otherwise scans this computer for installed JVMs, picks the lowest
 *       sufficient one and relaunches this same jar with it (same args).</li>
 *   <li>If no suitable Java exists, prints what was found and exits(3)
 *       with a human-readable message instead of
 *       {@code UnsupportedClassVersionError}.</li>
 * </ol>
 *
 * <p>Rule: this file must never reference application classes directly —
 * only by name via reflection — otherwise an old JVM fails while loading
 * <i>this</i> class too.
 */
public final class Bootstrap {

    private static final int REQUIRED = 17;
    private static final String PIN_ENV = "MCDECOMPILER_JVM_PINNED";
    private static final String REAL_MAIN = "app.cli.MainCli";

    private Bootstrap() {
    }

    public static void main(String[] args) throws Exception {
        int current = currentMajor();
        if (current < REQUIRED && System.getenv(PIN_ENV) == null) {
            String cmd = System.getProperty("sun.java.command", "");
            if (cmd.indexOf("GradleWorkerMain") < 0) {
                String best = findBestJava(REQUIRED);
                if (best == null) {
                    System.err.println("ERROR: This program needs Java " + REQUIRED
                            + "+ but is running on Java " + current + ".");
                    System.err.println(describe());
                    System.err.println("Install a Java " + REQUIRED
                            + "+ JDK/JRE and run the program again.");
                    System.exit(3);
                    return;
                }
                System.err.println("Current Java " + current
                        + " is too old; relaunching automatically with " + best + ".");
                relaunch(best, args, cmd);
                return; // unreachable
            }
        }
        Class.forName(REAL_MAIN).getMethod("main", String[].class)
                .invoke(null, new Object[]{args});
    }

    // ------------------------------------------------------------- version

    static int currentMajor() {
        return parseMajor(System.getProperty("java.version", ""));
    }

    /** Parses {@code 1.8.0_202} → 8, {@code 17.0.17} → 17. Never throws. */
    static int parseMajor(String v) {
        if (v == null) {
            return -1;
        }
        v = v.trim();
        try {
            if (v.startsWith("1.")) {
                String[] parts = v.split("[.\\-_]");
                return parts.length >= 2 ? Integer.parseInt(parts[1]) : -1;
            }
            Matcher m = Pattern.compile("(\\d+)").matcher(v);
            return m.find() ? Integer.parseInt(m.group(1)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------- discovery

    /** Returns the java executable of the lowest sufficient JVM, or null. */
    static String findBestJava(int minMajor) {
        Map<String, Integer> found = new LinkedHashMap<String, Integer>();
        List<String> exes = new ArrayList<String>();
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.trim().isEmpty()) {
            exes.add(jh.trim() + File.separator + "bin" + File.separator + exe());
        }
        exes.addAll(fromPathLookup());
        exes.addAll(fromWellKnownRoots());
        for (String exe : exes) {
            try {
                String home = homeOf(exe);
                if (home == null || found.containsKey(home)) {
                    continue;
                }
                int major = queryMajor(exe);
                if (major > 0) {
                    found.put(home, major);
                }
            } catch (Exception ignored) {
            }
        }
        String best = null;
        int bestMajor = Integer.MAX_VALUE;
        for (Map.Entry<String, Integer> e : found.entrySet()) {
            if (e.getValue() >= minMajor && e.getValue() < bestMajor) {
                bestMajor = e.getValue();
                best = e.getKey() + File.separator + "bin" + File.separator + exe();
            }
        }
        return best;
    }

    private static String exe() {
        return isWindows() ? "java.exe" : "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static List<String> fromPathLookup() {
        List<String> out = new ArrayList<String>();
        try {
            ProcessBuilder pb = isWindows()
                    ? new ProcessBuilder("where", "java")
                    : new ProcessBuilder("which", "-a", "java");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String text = readAll(p.getInputStream());
            p.waitFor(15, TimeUnit.SECONDS);
            for (String line : text.split("\\R")) {
                line = line.trim();
                if (!line.isEmpty() && line.toLowerCase(Locale.ROOT).indexOf("not found") < 0) {
                    out.add(line);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static List<String> fromWellKnownRoots() {
        List<String> out = new ArrayList<String>();
        List<String> roots = new ArrayList<String>();
        if (isWindows()) {
            Collections.addAll(roots,
                    "C:\\Program Files\\Java",
                    "C:\\Program Files (x86)\\Java",
                    "C:\\Program Files\\Eclipse Adoptium",
                    "C:\\Program Files\\Eclipse Foundation",
                    "C:\\Program Files\\Amazon Corretto",
                    "C:\\Program Files\\Microsoft",
                    "C:\\Program Files\\Zulu",
                    "C:\\Program Files\\BellSoft",
                    "C:\\Java");
        } else {
            Collections.addAll(roots, "/usr/lib/jvm", "/opt");
        }
        for (String root : roots) {
            File dir = new File(root);
            File[] children = dir.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                out.add(child.getAbsolutePath() + File.separator + "bin" + File.separator + exe());
                File[] inner = child.listFiles();
                if (inner != null) {
                    for (File g : inner) {
                        out.add(g.getAbsolutePath() + File.separator + "bin" + File.separator + exe());
                    }
                }
            }
        }
        return out;
    }

    private static String homeOf(String exe) {
        try {
            Path p = Paths.get(exe).toRealPath();
            if (!Files.isRegularFile(p)) {
                return null;
            }
            Path bin = p.getParent();
            if (bin != null && "bin".equalsIgnoreCase(String.valueOf(bin.getFileName()))) {
                Path home = bin.getParent();
                return home == null ? null : home.toString();
            }
            return bin == null ? null : bin.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static int queryMajor(String exe) {
        try {
            Process p = new ProcessBuilder(exe, "-version").redirectErrorStream(true).start();
            String text = readAll(p.getInputStream());
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return -1;
            }
            Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(text);
            return parseMajor(m.find() ? m.group(1) : text);
        } catch (Exception e) {
            return -1;
        }
    }

    // ------------------------------------------------------------- relaunch

    private static void relaunch(String javaExe, String[] args, String sunCommand) throws Exception {
        String first = firstToken(sunCommand);
        List<String> command = new ArrayList<String>();
        command.add(javaExe);
        command.add("-Dfile.encoding=UTF-8");
        boolean jarMode = first.toLowerCase(Locale.ROOT).endsWith(".jar")
                && new File(first).isFile();
        if (jarMode) {
            command.add("-jar");
            command.add(first);
        } else {
            command.add("-cp");
            command.add(System.getProperty("java.class.path", "."));
            command.add(REAL_MAIN);
        }
        for (String a : args) {
            if (jarMode && a.equals(first)) {
                jarMode = false; // skip duplicated jar token once
                continue;
            }
            command.add(a);
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put(PIN_ENV, "1");
        pb.inheritIO();
        System.exit(pb.start().waitFor());
    }

    private static String firstToken(String sunCommand) {
        if (sunCommand == null || sunCommand.trim().isEmpty()) {
            return "";
        }
        String t = sunCommand.trim().split("\\s+")[0];
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Running on Java ").append(currentMajor())
                .append(" (").append(System.getProperty("java.home")).append(")\n");
        sb.append("JAVA_HOME=").append(System.getenv("JAVA_HOME")).append('\n');
        return sb.toString();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        in.close();
        return new String(buf.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Sorted view helper kept allocation-free for old JVMs. */
    @SuppressWarnings("unused")
    private static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> l =
                new ArrayList<Map.Entry<String, Integer>>(m.entrySet());
        Collections.sort(l, new Comparator<Map.Entry<String, Integer>>() {
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return a.getValue().compareTo(b.getValue());
            }
        });
        return l;
    }
}
