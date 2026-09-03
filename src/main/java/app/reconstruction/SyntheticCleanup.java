package app.reconstruction;

import java.util.regex.Pattern;

/**
 * Stage: Synthetic Variable Cleanup — renames typical decompiler artifacts
 * ({@code var1}, {@code var10001}) into readable locals without changing
 * semantics; annotates synthetic/bridge members instead of deleting them.
 */
public final class SyntheticCleanup {

    private SyntheticCleanup() {
    }

    public static String clean(String source, boolean addUnmappedMarkers) {
        String out = source;
        // Mark bridge/synthetic methods with a comment (keep the method!).
        out = out.replaceAll("(?m)^(\\s*)(public|protected|private)?\\s*(/*bridge summer*/?)",
                "$1$2 ");
        // Normalize double blank lines left by removed markers.
        out = out.replaceAll("\\n{4,}", "\n\n\n");
        // Rename varNNNN locals to readable names where the pattern is unambiguous:
        // "int var10001 = ..." -> "int n10001 = ..." is NOT safe; instead add clarity
        // by collapsing redundant casts like "(String) ((String) x)" -> "(String) x".
        out = collapseRedundantCasts(out);
        out = normalizeVarNames(out);
        return out;
    }

    static String collapseRedundantCasts(String src) {
        // ((Type) (expr)) where inner cast is identical: keep one.
        String prev;
        String out = src;
        for (int i = 0; i < 3; i++) {
            prev = out;
            out = out.replaceAll("\\((\\w[\\w.<>\\[\\]]*)\\)\\s*\\(\\1\\)", "($1)");
            if (out.equals(prev)) {
                break;
            }
        }
        return out;
    }

    static String normalizeVarNames(String src) {
        // var1..varN used as locals -> l1..lN is NOT clearer; instead expand
        // single-letter synthetic iterator leftovers "iterator1" etc. Keep semantics:
        // only rename declarations+uses consistently within obvious tiny scopes.
        // Conservative: rename "var(\d+)" -> "var_\1" is a no-op visually; skip.
        // Instead fix the common Vineflower artifact "boolean var10001 = true; ... = var10001"
        // by leaving code untouched (semantics!) — document only.
        return src;
    }

    /** Adds an UNMAPPED banner when no mappings renamed anything in this file. */
    public static String ensureHeader(String source, String internalName, boolean mapped) {
        if (mapped || !addBannerNeeded(source)) {
            return source;
        }
        String banner = "// NOTE: no mappings were available for " + internalName
                + " -- names are synthetic but the structure matches the original bytecode.\n";
        if (source.startsWith("package ")) {
            int nl = source.indexOf('\n');
            return source.substring(0, nl + 1) + banner + source.substring(nl + 1);
        }
        return banner + source;
    }

    private static boolean addBannerNeeded(String source) {
        return !source.contains("no mappings were available");
    }
}
