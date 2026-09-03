package app.decompiler;

import org.jetbrains.java.decompiler.api.Decompiler;
import org.jetbrains.java.decompiler.main.decompiler.DirectoryResultSaver;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;

import app.core.DecompileOptions;
import app.core.DecompileProgressListener;
import app.core.DecompileStats;
import app.core.GameVersion;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Primary engine backed by Vineflower (FernFlower fork).
 * Decompiles the whole JAR into a temp directory via
 * {@link DirectoryResultSaver}, then reads {@code *.java} files back.
 * Parallelism is delegated to Vineflower itself (multithreaded) plus
 * parallel file reads on our side.
 */
public final class VineflowerEngine implements DecompilerEngine {

    @Override
    public String name() {
        return "vineflower";
    }

    @Override
    public String label() {
        return "Vineflower";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("org.jetbrains.java.decompiler.api.Decompiler");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public Map<String, String> decompile(Path jar, GameVersion version, DecompileOptions options,
                                         DecompileStats stats, DecompileProgressListener listener,
                                         Map<String, String> extraOptions) throws Exception {
        Path workDir = Files.createTempDirectory("mcdecompiler-vf-");
        Path vfOut = workDir.resolve("vf-out");
        Files.createDirectories(vfOut);
        try {
            Decompiler.Builder builder = Decompiler.builder()
                    .inputs(jar.toFile())
                    .output(new DirectoryResultSaver(vfOut.toFile()))
                    .logger(new ForwardingLogger(listener))
                    // Sensible modern defaults; profile options override them.
                    .option(IFernflowerPreferences.REMOVE_BRIDGE, 0)
                    .option(IFernflowerPreferences.REMOVE_SYNTHETIC, 0)
                    .option(IFernflowerPreferences.DECOMPILE_INNER, 1)
                    .option(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, 1)
                    .option(IFernflowerPreferences.DECOMPILE_ENUM, 1)
                    .option(IFernflowerPreferences.ASCII_STRING_CHARACTERS, 1)
                    .option(IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH, 0);
            if (extraOptions != null) {
                for (Map.Entry<String, String> e : extraOptions.entrySet()) {
                    builder.option(mapOptionKey(e.getKey()), mapOptionValue(e.getValue()));
                }
            }
            Decompiler decompiler = builder.build();
            decompiler.decompile();

            if (listener.isCancelled()) {
                return Map.of();
            }
            // Read back all produced .java files in parallel.
            Map<String, String> out = new ConcurrentHashMap<>();
            AtomicInteger read = new AtomicInteger();
            java.util.List<Path> javaFiles;
            try (Stream<Path> s = Files.walk(vfOut)) {
                javaFiles = s.filter(p -> p.toString().endsWith(".java")).toList();
            }
            int total = Math.max(1, javaFiles.size());
            javaFiles.parallelStream().forEach(p -> {
                if (listener.isCancelled()) {
                    return;
                }
                try {
                    String src = Files.readString(p, StandardCharsets.UTF_8);
                    String rel = vfOut.relativize(p).toString()
                            .replace(File.separatorChar, '/');
                    String internal = rel.substring(0, rel.length() - ".java".length());
                    out.put(internal, src);
                    stats.incDecompiled();
                    int n = read.incrementAndGet();
                    listener.onProgress(0.1 + 0.8 * n / total, internal, n, total);
                } catch (Exception e) {
                    stats.addError(p + ": " + e.getMessage());
                    listener.onError(p.toString(), e.getMessage());
                }
            });
            return new LinkedHashMap<>(out);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private static String mapOptionKey(String key) {
        // Accept both short ("ascii-string-characters") and constant-style keys.
        return switch (key.toLowerCase().replace('_', '-')) {
            case "ascii-string-characters" -> IFernflowerPreferences.ASCII_STRING_CHARACTERS;
            case "decompile-generics", "decompile-generic-signatures" -> IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES;
            case "include-entire-classpath" -> IFernflowerPreferences.INCLUDE_ENTIRE_CLASSPATH;
            case "decompile-inner" -> IFernflowerPreferences.DECOMPILE_INNER;
            case "decompile-enum" -> IFernflowerPreferences.DECOMPILE_ENUM;
            default -> key;
        };
    }

    private static Object mapOptionValue(String v) {
        if (v == null) {
            return "1";
        }
        if (v.equals("1") || v.equals("0")) {
            return v;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
    }

    /** Forwards Vineflower log lines into our progress listener. */
    private static final class ForwardingLogger extends IFernflowerLogger {
        private final DecompileProgressListener listener;

        ForwardingLogger(DecompileProgressListener listener) {
            this.listener = listener;
        }

        @Override
        public void writeMessage(String message, Severity severity) {
            if (message == null) {
                return;
            }
            switch (severity) {
                case WARN -> listener.onWarning(message);
                case ERROR -> listener.onError("vineflower", message);
                default -> {
                    if (message.startsWith("ERROR") || message.startsWith("WARN")) {
                        listener.onWarning(message);
                    }
                }
            }
        }

        @Override
        public void writeMessage(String message, Severity severity, Throwable t) {
            writeMessage(message + (t == null ? "" : " (" + t.getMessage() + ")"), severity);
        }
    }
}
