package app.reconstruction;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Stage: Import Cleanup — sorts, de-duplicates, drops self/duplicate imports. */
public final class ImportCleaner {

    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+(static\\s+)?([\\w.]+(\\.\\*)?)\\s*;\\s*$");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    private ImportCleaner() {
    }

    public static String clean(String source) {
        Matcher pm = PACKAGE.matcher(source);
        String pkg = pm.find() ? pm.group(1) : "";
        // Collect imports.
        Set<String> imports = new TreeSet<>();
        Matcher im = IMPORT.matcher(source);
        while (im.find()) {
            String statik = im.group(1) == null ? "" : "static ";
            imports.add("import " + statik + im.group(2) + ";");
        }
        // Drop imports from same package or java.lang (redundant) and self-imports.
        List<String> kept = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String imp : imports) {
            String cls = imp.replaceFirst("^import\\s+(static\\s+)?", "").replace(";", "").trim();
            if (cls.startsWith("java.lang.") && !cls.substring("java.lang.".length()).contains(".")) {
                continue;
            }
            if (!pkg.isEmpty()) {
                String noStatic = cls;
                int lastDot = noStatic.lastIndexOf('.');
                String impPkg = lastDot > 0 ? noStatic.substring(0, lastDot) : "";
                if (impPkg.equals(pkg) && !cls.endsWith(".*")) {
                    continue;
                }
            }
            if (seen.add(cls)) {
                kept.add(imp);
            }
        }
        // Remove all old import lines, then re-insert a single sorted block.
        String body = IMPORT.matcher(source).replaceAll("").replaceAll("(?m)^\\s*$\\n(?=\\s*$\\n)", "");
        if (kept.isEmpty()) {
            return body.replaceAll("\\n{3,}", "\n\n");
        }
        String block = String.join("\n", kept) + "\n";
        Matcher pm2 = PACKAGE.matcher(body);
        if (pm2.find()) {
            int insert = pm2.end();
            return body.substring(0, insert) + "\n\n" + block + body.substring(insert).replaceAll("^(\\s*\\n)+", "\n");
        }
        return block + "\n" + body.replaceAll("^(\\s*\\n)+", "");
    }
}
