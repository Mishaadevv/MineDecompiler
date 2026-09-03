package app.util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Streaming JAR helpers — never loads whole archives into memory. */
public final class JarUtils {

    private JarUtils() {
    }

    public static List<String> listClassEntries(Path jar) throws IOException {
        List<String> out = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (!e.isDirectory() && e.getName().endsWith(".class")
                        && !e.getName().startsWith("META-INF/")) {
                    out.add(e.getName());
                }
            }
        }
        return out;
    }

    public static byte[] readEntry(Path jar, String entryName) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry e = jf.getJarEntry(entryName);
            if (e == null) {
                return null;
            }
            try (InputStream in = jf.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
    }

    public static boolean hasEntry(Path jar, String entryName) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            return jf.getJarEntry(entryName) != null;
        }
    }

    public static void copyEntryToFile(Path jar, String entryName, Path dest) throws IOException {
        byte[] data = readEntry(jar, entryName);
        if (data != null) {
            Files.createDirectories(dest.getParent());
            Files.write(dest, data);
        }
    }
}
