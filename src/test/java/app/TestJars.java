package app;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.jar.Attributes;

/**
 * Builds tiny synthetic "minecraft-like" JARs — one per era — by compiling
 * small Java sources with the in-JDK compiler. No real Minecraft binaries,
 * no proprietary assets; purely structural fixtures.
 */
public final class TestJars {

    private TestJars() {
    }

    public record Source(String className, String code) {
    }

    /** Compiles sources and packs them plus extra entries into a JAR. */
    public static Path buildJar(Path dir, String fileName, List<Source> sources,
                                Map<String, String> manifestAttrs,
                                Map<String, byte[]> extraEntries) throws IOException {
        Path srcDir = dir.resolve(fileName + "-src");
        Path clsDir = dir.resolve(fileName + "-classes");
        Files.createDirectories(srcDir);
        Files.createDirectories(clsDir);
        if (!sources.isEmpty()) {
            List<String> files = new ArrayList<>();
            for (Source s : sources) {
                Path f = srcDir.resolve(s.className().replace('.', '/') + ".java");
                Files.createDirectories(f.getParent());
                Files.writeString(f, s.code(), StandardCharsets.UTF_8);
                files.add(f.toString());
            }
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                throw new IOException("No Java compiler available (tests need a JDK, not a JRE).");
            }
            List<String> args = new ArrayList<>(List.of("-d", clsDir.toString()));
            args.addAll(files);
            int rc = compiler.run(null, null, null, args.toArray(new String[0]));
            if (rc != 0) {
                throw new IOException("Test compilation failed for " + fileName);
            }
        }
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (manifestAttrs != null) {
            manifestAttrs.forEach((k, v) -> mf.getMainAttributes().putValue(k, v));
        }
        Path jar = dir.resolve(fileName);
        try (OutputStream fos = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(fos, mf)) {
            try (var s = Files.walk(clsDir)) {
                for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    String entry = clsDir.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                    jos.putNextEntry(new JarEntry(entry));
                    Files.copy(p, jos);
                    jos.closeEntry();
                }
            }
            if (extraEntries != null) {
                for (Map.Entry<String, byte[]> e : extraEntries.entrySet()) {
                    jos.putNextEntry(new JarEntry(e.getKey()));
                    jos.write(e.getValue());
                    jos.closeEntry();
                }
            }
        }
        return jar;
    }

    public static final String HELLO = """
            package net.minecraft.client;
            public class Minecraft {
                private int fps;
                public void tick() {
                    fps++;
                }
                public int getFps() {
                    return fps;
                }
            }
            """;

    public static final String WORLD = """
            package net.minecraft.world;
            import net.minecraft.client.Minecraft;
            public class World {
                private final Minecraft mc;
                public World(Minecraft mc) {
                    this.mc = mc;
                }
                public int fps() {
                    return mc.getFps();
                }
            }
            """;

    /** Modern-era fixture: net.minecraft packages + version.json + manifest. */
    public static Path modernJar(Path dir) throws IOException {
        return buildJar(dir, "modern-1.20.1.jar",
                List.of(new Source("net.minecraft.client.Minecraft", HELLO),
                        new Source("net.minecraft.world.World", WORLD)),
                Map.of("Implementation-Version", "1.20.1"),
                Map.of("version.json", "{\"id\": \"1.20.1\", \"type\": \"release\"}"
                        .getBytes(StandardCharsets.UTF_8)));
    }

    /** Legacy release fixture: 1.6.4-style manifest, no version.json. */
    public static Path legacyJar(Path dir) throws IOException {
        return buildJar(dir, "legacy-1.6.4.jar",
                List.of(new Source("net.minecraft.src.Minecraft", """
                        package net.minecraft.src;
                        public class Minecraft {
                            public void runTick() {}
                        }
                        """)),
                Map.of("Implementation-Version", "1.6.4"),
                Map.of());
    }

    /** Beta fixture: flat root class + filename hint. */
    public static Path betaJar(Path dir) throws IOException {
        return buildJar(dir, "minecraft-b1.7.3.jar",
                List.of(new Source("mi", """
                        public class mi {
                            public int a;
                            public void a() {
                                a++;
                            }
                        }
                        """)),
                Map.of(),
                Map.of());
    }

    /** Alpha fixture: flat root class, no metadata at all. */
    public static Path alphaJar(Path dir) throws IOException {
        return buildJar(dir, "minecraft-alpha.jar",
                List.of(new Source("Minecraft", """
                        public class Minecraft {
                            public void run() {}
                        }
                        """)),
                Map.of(),
                Map.of());
    }
}
