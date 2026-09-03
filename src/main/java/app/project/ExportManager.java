package app.project;

import app.core.DecompiledProject;
import app.core.GameVersion;
import app.core.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Opens an existing output directory as a browsable {@link Project}
 * (spec section 9 "Open Project") and exports single classes.
 */
public final class ExportManager {

    private ExportManager() {
    }

    public static Project openProject(Path outputDir) throws IOException {
        Path root = outputDir.toAbsolutePath();
        Path sourcesRoot = root.resolve("src/main/java");
        if (!Files.isDirectory(sourcesRoot)) {
            // Tolerate flat/partial outputs: treat root itself as sources root.
            sourcesRoot = root;
        }
        List<String> classes = new ArrayList<>();
        if (Files.isDirectory(sourcesRoot)) {
            try (Stream<Path> s = Files.walk(sourcesRoot)) {
                for (Path p : (Iterable<Path>) s.filter(q -> q.toString().endsWith(".java"))::iterator) {
                    String rel = sourcesRoot.relativize(p).toString()
                            .replace(java.io.File.separatorChar, '/');
                    classes.add(rel.substring(0, rel.length() - 5).replace('/', '.'));
                }
            }
        }
        classes.sort(String::compareTo);
        GameVersion version = readVersion(root);
        return new Project(root, sourcesRoot, version, classes);
    }

    private static GameVersion readVersion(Path root) {
        try {
            Path v = root.resolve("metadata/version.txt");
            if (Files.isRegularFile(v)) {
                String text = Files.readString(v, StandardCharsets.UTF_8).trim();
                String id = text.split("\\s+")[0];
                return GameVersion.classify(id);
            }
        } catch (Exception ignored) {
        }
        return GameVersion.classify("unknown");
    }

    public static Path exportClass(DecompiledProject project, String internalName, Path destFile) throws IOException {
        Path src = project.getClassToSource().get(internalName);
        if (src == null || !Files.isRegularFile(src)) {
            throw new IOException("Class not found in project: " + internalName);
        }
        Files.createDirectories(destFile.getParent());
        Files.copy(src, destFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return destFile;
    }
}
