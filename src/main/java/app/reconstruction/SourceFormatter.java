package app.reconstruction;

/** Stage: Formatting — deterministic light formatting (no semantic changes). */
public final class SourceFormatter {

    private SourceFormatter() {
    }

    public static String format(String source) {
        String out = source.replace("\r\n", "\n").replace('\r', '\n');
        // Tabs -> 4 spaces for consistency.
        out = out.replace("\t", "    ");
        // Trailing whitespace.
        out = out.replaceAll("(?m)[ \\t]+$", "");
        // Collapse 3+ blank lines.
        out = out.replaceAll("\\n{4,}", "\n\n\n");
        // Ensure single trailing newline.
        if (!out.endsWith("\n")) {
            out += "\n";
        }
        return out;
    }
}
