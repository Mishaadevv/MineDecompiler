package app.reconstruction;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage: Package Reconstruction — maps an internal class name to its output
 * {@code .java} path under {@code src/main/java}, sanitizing segments that
 * are illegal in file systems or Java identifiers (alpha-era flat names,
 * numeric segments, Windows-reserved names).
 */
public final class PackageReconstructor {

    private PackageReconstructor() {
    }

    public static Path toSourcePath(Path sourcesRoot, String internalName) {
        String[] parts = internalName.split("/");
        StringBuilder rel = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String seg = sanitizeSegment(parts[i], i == parts.length - 1);
            if (rel.length() > 0) {
                rel.append('/');
            }
            rel.append(seg);
        }
        String s = rel.toString();
        if (!s.endsWith(".java")) {
            s += ".java";
        }
        return sourcesRoot.resolve(s);
    }

    static String sanitizeSegment(String seg, boolean isClass) {
        // Split inner classes: Outer$Inner -> Outer/Inner (separate files are NOT
        // created; Vineflower already nests them — keep file as Outer.java).
        if (isClass && seg.contains("$")) {
            seg = seg.substring(0, seg.indexOf('$'));
        }
        if (seg.isEmpty()) {
            return "_";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_' || (isClass && c == '$');
            sb.append(ok ? c : '_');
        }
        String out = sb.toString();
        if (out.isEmpty()) {
            out = "_";
        }
        if (Character.isDigit(out.charAt(0))) {
            out = "_" + out;
        }
        // Windows-reserved device names.
        if (out.matches("(?i)CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) {
            out = "_" + out;
        }
        // Java keywords as package segments.
        if (!isClass && isKeyword(out)) {
            out = out + "_";
        }
        if (out.equals("package") || out.equals("class") && false) {
            out = out + "_";
        }
        return out;
    }

    private static boolean isKeyword(String s) {
        return switch (s) {
            case "abstract", "assert", "boolean", "break", "byte", "case", "catch",
                    "char", "class", "const", "continue", "default", "do", "double",
                    "else", "enum", "extends", "final", "finally", "float", "for",
                    "goto", "if", "implements", "import", "instanceof", "int",
                    "interface", "long", "native", "new", "package", "private",
                    "protected", "public", "return", "short", "static", "strictfp",
                    "super", "switch", "synchronized", "this", "throw", "throws",
                    "transient", "try", "void", "volatile", "while", "record",
                    "sealed", "permits", "var", "yield" -> true;
            default -> false;
        };
    }

    /** Derives the expected package declaration for an internal name. */
    public static String expectedPackage(String internalName) {
        int slash = internalName.lastIndexOf('/');
        if (slash <= 0) {
            return "";
        }
        return internalName.substring(0, slash).replace('/', '.');
    }
}
