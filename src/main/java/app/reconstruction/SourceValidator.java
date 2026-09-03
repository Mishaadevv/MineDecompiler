package app.reconstruction;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage: Source Validation — cheap syntactic sanity checks (brace balance,
 * package/class presence). Anything suspicious becomes a warning, never a
 * dropped file.
 */
public final class SourceValidator {

    private SourceValidator() {
    }

    public static List<String> validate(String internalName, String source) {
        List<String> issues = new ArrayList<>();
        int depth = 0;
        boolean inLine = false;
        boolean inBlock = false;
        boolean inStr = false;
        boolean inChar = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (inLine) {
                if (c == '\n') inLine = false;
                continue;
            }
            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (inChar) {
                if (c == '\\') {
                    i++;
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && next == '/') {
                inLine = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlock = true;
                i++;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '\'') {
                inChar = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth < 0) {
                    issues.add(internalName + ": unbalanced closing brace");
                    depth = 0;
                }
            }
        }
        if (depth != 0) {
            issues.add(internalName + ": unbalanced braces (depth " + depth + ")");
        }
        if (!source.contains("class ") && !source.contains("interface ")
                && !source.contains("enum ") && !source.contains("@interface")
                && !source.contains("record ")) {
            issues.add(internalName + ": no type declaration found");
        }
        return issues;
    }
}
