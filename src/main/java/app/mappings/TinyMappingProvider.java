package app.mappings;

import app.core.GameVersion;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tiny mappings v1/v2 (community mapping file format, e.g. OrnitheMC Feather
 * for beta/legacy/alpha versions). Verified against real Feather packs:
 * <pre>
 * v1  official  intermediary  named            (v1, TAB-separated)
 * CLASS  &lt;official&gt;  &lt;intermediary&gt;  &lt;named&gt;
 * FIELD  &lt;owner&gt;  &lt;desc&gt;  &lt;official&gt;  &lt;intermediary&gt;  &lt;named&gt;
 * METHOD &lt;owner&gt;  &lt;desc&gt;  &lt;official&gt;  &lt;intermediary&gt;  &lt;named&gt;
 * </pre>
 * We map the first namespace (obfuscated) to the last one (named).
 * {@code .gz} packs are read transparently.
 */
public final class TinyMappingProvider implements MappingProvider {

    private final Path file;

    public TinyMappingProvider(Path file) {
        this.file = file;
    }

    @Override
    public String name() {
        return "tiny";
    }

    @Override
    public MappingSet loadMappings(GameVersion version) throws IOException {
        MappingSet out = new MappingSet();
        out.putMetadata("provider", "tiny");
        if (file == null || !Files.isRegularFile(file)) {
            out.putMetadata("note", "Tiny mappings file not found: " + file);
            return out;
        }
        parseTiny(file, out);
        out.putMetadata("source", file.toString());
        return out;
    }

    static void parseTiny(Path file, MappingSet out) throws IOException {
        try (BufferedReader br = MappingFiles.openReader(file)) {
            String header = br.readLine();
            if (header == null) {
                throw new IOException("Empty Tiny mappings file: " + file);
            }
            String[] h = header.split("\t");
            if (h[0].equals("tiny") && h.length > 1 && h[1].equals("2")) {
                parseV2(br, out);
            } else if (h[0].equals("v1") && h.length >= 2) {
                parseV1(br, h.length - 1, out);
            } else if (h[0].equals("tiny")) {
                parseV2(br, out);
            } else {
                throw new IOException("Not a Tiny mappings file: " + file);
            }
        }
    }

    /** v1: CLASS/FIELD/METHOD rows, one column per namespace. */
    private static void parseV1(BufferedReader br, int namespaces, MappingSet out) throws IOException {
        int from = 0;
        int to = namespaces - 1;
        String line;
        while ((line = br.readLine()) != null) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] p = line.split("\t", -1);
            if (p.length == 0) {
                continue;
            }
            switch (p[0]) {
                case "CLASS":
                    if (p.length >= 3) {
                        String obf = col(p, 1 + from).replace('.', '/');
                        String named = col(p, 1 + Math.min(to, p.length - 2)).replace('.', '/');
                        out.mapClass(obf, named);
                    }
                    break;
                case "METHOD":
                    // METHOD owner desc ns0 ns1 ...
                    if (p.length >= 4) {
                        String owner = col(p, 1).replace('.', '/');
                        String desc = col(p, 2);
                        String obfName = col(p, 3 + from);
                        String named = col(p, 3 + Math.min(to, p.length - 4));
                        if (!obfName.isEmpty() && !named.isEmpty()) {
                            out.mapMethod(owner, obfName, desc, named);
                            out.mapMethod(owner, obfName, "", named);
                        }
                    }
                    break;
                case "FIELD":
                    if (p.length >= 4) {
                        String owner = col(p, 1).replace('.', '/');
                        String desc = col(p, 2);
                        String obfName = col(p, 3 + from);
                        String named = col(p, 3 + Math.min(to, p.length - 4));
                        if (!obfName.isEmpty() && !named.isEmpty()) {
                            out.mapField(owner, obfName, desc, named);
                            out.mapField(owner, obfName, "", named);
                        }
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /** v2: tiny/CLASS/METHOD/FIELD rows (Fabric-style). */
    private static void parseV2(BufferedReader br, MappingSet out) throws IOException {
        String line;
        String currentOwner = "";
        while ((line = br.readLine()) != null) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == '\t') {
                indent++;
            }
            String[] p = line.substring(indent).split("\t", -1);
            if (p.length == 0) {
                continue;
            }
            switch (p[0]) {
                case "c":
                    // c ns0 ns1 ... (last column = named)
                    if (p.length >= 3) {
                        String obf = p[1].replace('.', '/');
                        String named = p[p.length - 1].replace('.', '/');
                        out.mapClass(obf, named);
                        if (indent == 0) {
                            currentOwner = obf;
                        }
                    }
                    break;
                case "m":
                    // m desc ns0 ns1 ...  (owner = enclosing class when nested)
                    if (p.length >= 4) {
                        String desc = p[1];
                        String obfName = p[2];
                        String named = p[p.length - 1];
                        String owner = currentOwner;
                        if (obfName.contains("/")) {
                            int s = obfName.lastIndexOf('/');
                            owner = obfName.substring(0, s);
                            obfName = obfName.substring(s + 1);
                        }
                        out.mapMethod(owner, obfName, desc, named);
                        out.mapMethod(owner, obfName, "", named);
                    }
                    break;
                case "f":
                    if (p.length >= 4) {
                        String desc = p[1];
                        String obfName = p[2];
                        String named = p[p.length - 1];
                        String owner = currentOwner;
                        if (obfName.contains("/")) {
                            int s = obfName.lastIndexOf('/');
                            owner = obfName.substring(0, s);
                            obfName = obfName.substring(s + 1);
                        }
                        out.mapField(owner, obfName, desc, named);
                        out.mapField(owner, obfName, "", named);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private static String col(String[] p, int i) {
        return (i >= 0 && i < p.length) ? p[i].trim() : "";
    }

    public static boolean looksLikeTiny(Path file) {
        String head = MappingFiles.peekHead(file, 3);
        if (head.isEmpty()) {
            return false;
        }
        String first = head.split("\n", -1)[0];
        if (first.startsWith("tiny")) {
            return true;
        }
        if (first.startsWith("v1\t") || first.equals("v1")) {
            return true;
        }
        // v1 rows without visible header (rare): CLASS\t... with 3+ columns.
        for (String line : head.split("\n")) {
            if ((line.startsWith("CLASS\t") || line.startsWith("METHOD\t")
                    || line.startsWith("FIELD\t")) && line.split("\t", -1).length >= 3) {
                return true;
            }
        }
        return false;
    }
}
