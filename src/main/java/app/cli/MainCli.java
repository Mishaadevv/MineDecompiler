package app.cli;

import app.core.DecompileOptions;
import app.core.DecompileProgressListener;
import app.core.DecompiledProject;
import app.decompiler.DecompilerRegistry;
import app.pipeline.DecompilationPipeline;
import app.util.JavaAutoLauncher;
import app.util.JavaDiscovery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI entry point (spec section 18). Shares {@link DecompilationPipeline}
 * with the GUI — no duplicate logic.
 *
 * <pre>
 * mcdecompiler input.jar --output ./minecraft-src
 * mcdecompiler client.jar --version 1.1 --decompiler vineflower
 * mcdecompiler client.jar --mappings ./mappings --threads 8 --no-cache
 * </pre>
 */
public final class MainCli {

    public static void main(String[] args) {
        if (JavaAutoLauncher.ensureJava(JavaDiscovery.REQUIRED_MAJOR, "app.cli.MainCli", args)) {
            return;
        }
        int code = run(args, DecompileProgressListener.console());
        if (code != 0) {
            System.exit(code);
        }
    }

    static int run(String[] args, DecompileProgressListener listener) {
        if (args.length == 0 || has(args, "--help", "-h")) {
            printHelp();
            return 0;
        }
        if (has(args, "--list-decompilers")) {
            System.out.println("Available decompilers: "
                    + new DecompilerRegistry().availableNames());
            return 0;
        }
        Path input = Path.of(args[0]);
        if (!Files.isRegularFile(input)) {
            System.err.println("Input JAR not found: " + input);
            printHelp();
            return 2;
        }
        String outputArg = opt(args, "--output", "-o");
        if (outputArg == null) {
            System.err.println("Output directory not specified.\n\nUse:\n  mcdecompiler input.jar --output <directory>");
            // Safe default suggestion (never writes without consent).
            System.err.println("\nNo files were written.");
            return 2;
        }
        Path output = Path.of(outputArg);
        String version = opt(args, "--version", null);
        String decompiler = opt(args, "--decompiler", null);
        if (decompiler == null) {
            decompiler = "auto";
        }
        String mappings = opt(args, "--mappings", null);
        String mappingsLib = opt(args, "--mappings-lib", null);
        int threads = parseInt(opt(args, "--threads", null),
                Runtime.getRuntime().availableProcessors());
        boolean noCache = has(args, "--no-cache");
        boolean offline = has(args, "--offline", "--no-download");

        DecompileOptions options = DecompileOptions.builder(input, output)
                .decompiler(decompiler)
                .customMappings(mappings == null ? null : Path.of(mappings))
                .mappingsLibrary(mappingsLib == null ? null : Path.of(mappingsLib))
                .versionOverride(version)
                .threads(threads)
                .useCache(!noCache)
                .autoDownloadMappings(!offline)
                .build();
        try {
            DecompilationPipeline pipeline = new DecompilationPipeline();
            DecompiledProject project = pipeline.run(options, listener);
            System.out.println();
            System.out.println("Decompilation completed.");
            System.out.println("Output: " + project.getOutputDirectory());
            System.out.println("Version: " + project.getVersion());
            System.out.println(project.getStats().summary());
            if (!project.getFailedClasses().isEmpty()) {
                System.out.println("Failed (fallback stubs): " + project.getFailedClasses().size());
            }
            return 0;
        } catch (Exception e) {
            System.err.println("Decompilation failed: " + e.getMessage());
            if (has(args, "--verbose", "-v")) {
                e.printStackTrace();
            }
            return 1;
        }
    }

    private static boolean has(String[] args, String... names) {
        for (String a : args) {
            for (String n : names) {
                if (a.equals(n)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String opt(String[] args, String... names) {
        for (int i = 0; i < args.length; i++) {
            for (String n : names) {
                if (args[i].equals(n) && i + 1 < args.length) {
                    return args[i + 1];
                }
                if (args[i].startsWith(n + "=")) {
                    return args[i].substring(n.length() + 1);
                }
            }
        }
        return null;
    }

    private static int parseInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void printHelp() {
        System.out.println("""
                mcdecompiler - Minecraft Source Reconstructor (local-only, offline-first)

                Usage:
                  mcdecompiler <input.jar> --output <directory> [options]

                Options:
                  --output, -o <dir>     Output directory (required). Created if missing.
                  --version <id>         Override auto-detected version (e.g. 1.1, b1.7.3).
                  --decompiler <name>    auto | vineflower | cfr | javap  (default: auto)
                  --mappings <file|dir>  Mojang client.txt/server.txt, Tiny *.tiny, SRG *.srg, *.properties
                  --mappings-lib <dir>   Folder auto-scanned for mappings (Find Mappings)
                  --threads <n>          Decompilation threads (default: CPU count)
                  --no-cache             Disable ~/.minecraft-decompiler disk cache
                  --offline, --no-download
                                         Never download mappings (local files only)
                  --list-decompilers     List available engines
                  --verbose, -v          Print stack traces
                  --help, -h             This help

                Examples:
                  mcdecompiler client.jar --output ./minecraft-src
                  mcdecompiler client.jar --version 1.1
                  mcdecompiler client.jar --decompiler vineflower --mappings ./mappings

                The input JAR is never modified. Missing mappings are downloaded
                automatically (cached); use --offline to disable.""");
    }

    /** For tests: parses args without running. */
    static DecompileOptions parseForTest(Path input, Path output, List<String> extra) {
        List<String> all = new ArrayList<>();
        all.add(input.toString());
        all.add("--output");
        all.add(output.toString());
        all.addAll(extra);
        String decompiler = opt(all.toArray(new String[0]), "--decompiler");
        return DecompileOptions.builder(input, output)
                .decompiler(decompiler == null ? "auto" : decompiler)
                .build();
    }
}
