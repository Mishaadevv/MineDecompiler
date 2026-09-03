package app.search;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lightweight project search (spec: search classes/methods + full-project search).
 * Operates on the file tree with lazy reads — no in-memory full-text index,
 * so even 5k-class projects stay lean.
 */
public final class SearchIndex {

    public record Hit(String className, Path file, int line, String preview) {
    }

    private final Map<String, Path> classToSource;

    public SearchIndex(Map<String, Path> classToSource) {
        this.classToSource = new TreeMap<>(classToSource);
    }

    /** Prefix/substring search over class names (Ctrl+P quick-open). */
    public List<String> findClasses(String query, int limit) {
        String q = query == null ? "" : query.toLowerCase().replace('.', '/').replace('.', '.');
        String qDots = query == null ? "" : query.toLowerCase();
        List<String> exact = new ArrayList<>();
        List<String> contains = new ArrayList<>();
        for (String internal : classToSource.keySet()) {
            String dotted = internal.replace('/', '.').toLowerCase();
            String simple = dotted.substring(dotted.lastIndexOf('.') + 1);
            if (dotted.equals(qDots) || simple.equals(qDots)) {
                exact.add(internal.replace('/', '.'));
            } else if (dotted.contains(qDots) || simple.contains(qDots)) {
                contains.add(internal.replace('/', '.'));
            }
            if (exact.size() + contains.size() >= limit * 2) {
                break;
            }
        }
        List<String> out = new ArrayList<>(exact);
        out.addAll(contains);
        return out.subList(0, Math.min(limit, out.size()));
    }

    /** Full-text search across all sources (Ctrl+Shift+F), streaming per file. */
    public List<Hit> searchAll(String query, int maxHits) throws IOException {
        List<Hit> hits = new ArrayList<>();
        if (query == null || query.isEmpty()) {
            return hits;
        }
        for (Map.Entry<String, Path> e : classToSource.entrySet()) {
            List<String> lines;
            try {
                lines = Files.readAllLines(e.getValue(), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                continue;
            }
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(query)) {
                    hits.add(new Hit(e.getKey().replace('/', '.'), e.getValue(), i + 1,
                            lines.get(i).strip()));
                    if (hits.size() >= maxHits) {
                        return hits;
                    }
                }
            }
        }
        return hits;
    }

    /** Lists method-like declarations in one source file for the outline view. */
    public static List<String> listMethods(Path javaFile) {
        List<String> out = new ArrayList<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(javaFile, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return out;
        }
        java.util.regex.Pattern m = java.util.regex.Pattern.compile(
                "^\\s*(public|protected|private)?\\s*(static\\s+)?(final\\s+)?[\\w.<>\\[\\]]+\\s+(\\w+)\\s*\\(");
        for (String line : lines) {
            var matcher = m.matcher(line);
            if (matcher.find()) {
                out.add(matcher.group(4) + line.substring(line.indexOf('('),
                        Math.min(line.length(), line.indexOf('(') + 40)));
            }
            if (out.size() >= 500) {
                break;
            }
        }
        return out;
    }
}
