package app.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Guarantees the tool runs on a suitable JVM: if the current runtime is older
 * than required and a better Java exists on this computer, the application
 * transparently relaunches itself under that Java (same program, same args).
 * If nothing suitable exists, it prints exactly what was found and exits
 * with a clear message instead of a cryptic {@code UnsupportedClassVersionError}.
 *
 * <p>Usage — first line of {@code main()}:
 * <pre>
 * if (JavaAutoLauncher.ensureJava(JavaDiscovery.REQUIRED_MAJOR, "app.ui.App", args)) return;
 * </pre>
 */
public final class JavaAutoLauncher {

    /** Set in the relaunched child so it never relaunches again (loop guard). */
    public static final String PIN_ENV = "MCDECOMPILER_JVM_PINNED";

    private JavaAutoLauncher() {
    }

    /**
     * @return {@code true} when the caller must return immediately (a relaunch
     *         was performed and this process is exiting, or startup is
     *         impossible and we already exited).
     */
    public static boolean ensureJava(int minMajor, String mainClass, String[] args) {
        if (System.getenv(PIN_ENV) != null) {
            return false;
        }
        if (JavaDiscovery.currentMajor() >= minMajor) {
            return false;
        }
        // Never try to outsmart a build tool's worker JVM.
        String cmd = System.getProperty("sun.java.command", "");
        if (cmd.contains("GradleWorkerMain")) {
            return false;
        }
        Optional<JavaDiscovery.JvmInfo> best = JavaDiscovery.bestFor(minMajor);
        if (best.isEmpty()) {
            System.err.println("ERROR: This program needs Java " + minMajor
                    + "+ but is running on Java " + JavaDiscovery.currentMajor() + ".");
            System.err.println("No suitable Java installation was found on this computer.");
            System.err.println(JavaDiscovery.describe());
            System.err.println("Install a Java " + minMajor + "+ JDK/JRE and run the program again.");
            System.exit(3);
            return true;
        }
        JavaDiscovery.JvmInfo jvm = best.get();
        System.err.println("Current Java " + JavaDiscovery.currentMajor()
                + " is too old; relaunching automatically with " + jvm.shortName() + ".");
        List<String> command = buildCommand(jvm.javaExe().toString(), mainClass, args, cmd);
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.environment().put(PIN_ENV, "1");
            pb.inheritIO();
            int code = pb.start().waitFor();
            System.exit(code);
        } catch (Exception e) {
            System.err.println("Automatic relaunch failed: " + e.getMessage());
            System.err.println(JavaDiscovery.describe());
            System.exit(3);
        }
        return true;
    }

    /** Visible for testing: builds the relaunch command line. */
    public static List<String> buildCommand(String javaExe, String mainClass, String[] args, String sunCommand) {
        List<String> command = new ArrayList<>();
        command.add(javaExe);
        command.add("-Dfile.encoding=UTF-8");
        String first = firstToken(sunCommand);
        if (first.toLowerCase().endsWith(".jar") && Files.isRegularFile(Path.of(first))) {
            command.add("-jar");
            command.add(first);
        } else {
            command.add("-cp");
            command.add(System.getProperty("java.class.path", "."));
            command.add(mainClass);
        }
        // When launched from a jar, skip the jar token itself in forwarded args.
        boolean skipFirst = first.toLowerCase().endsWith(".jar");
        int forwarded = 0;
        for (String a : args) {
            if (skipFirst && forwarded == 0 && a.equals(first)) {
                forwarded++;
                continue;
            }
            command.add(a);
            forwarded++;
        }
        return command;
    }

    private static String firstToken(String sunCommand) {
        if (sunCommand == null || sunCommand.isBlank()) {
            return "";
        }
        String t = sunCommand.strip().split("\\s+")[0];
        // Strip quotes the launcher may add around paths with spaces.
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            t = t.substring(1, t.length() - 1);
        }
        // sun.java.command for -jar may be just the jar path.
        if (t.contains(File.separator) || t.toLowerCase().endsWith(".jar")) {
            return t;
        }
        return t;
    }
}
