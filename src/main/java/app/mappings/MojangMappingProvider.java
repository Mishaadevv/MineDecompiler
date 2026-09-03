package app.mappings;

import app.core.GameVersion;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Mojang ProGuard mapping files (client.txt / server.txt):
 * <pre>
 * net.minecraft.client.Minecraft -&gt; abc:
 *     void tick() -&gt; a
 *     int fps -&gt; b
 * </pre>
 * Direction in Mojang files is named -&gt; obf, so we invert it.
 */
public final class MojangMappingProvider implements MappingProvider {

    private final Path file;

    public MojangMappingProvider(Path file) {
        this.file = file;
    }

    @Override
    public String name() {
        return "mojang";
    }

    @Override
    public boolean supports(GameVersion version) {
        return version.getEra() == GameVersion.Era.MODERN_RELEASE
                || version.getEra() == GameVersion.Era.FUTURE
                || version.getEra() == GameVersion.Era.UNKNOWN;
    }

    @Override
    public MappingSet loadMappings(GameVersion version) throws IOException {
        MappingSet out = new MappingSet();
        out.putMetadata("provider", "mojang");
        if (file == null || !Files.isRegularFile(file)) {
            out.putMetadata("note", "Mojang mappings file not found: " + file);
            return out;
        }
        parseProGuard(file, out);
        out.putMetadata("source", file.toString());
        return out;
    }

    static void parseProGuard(Path file, MappingSet out) throws IOException {
        String currentObf = null;
        String currentNamed = null;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (!line.startsWith(" ")) {
                    // class mapping: named -> obf:
                    int arrow = line.indexOf("->");
                    if (arrow < 0) {
                        continue;
                    }
                    String named = line.substring(0, arrow).trim().replace('.', '/');
                    String obf = line.substring(arrow + 2).trim();
                    if (obf.endsWith(":")) {
                        obf = obf.substring(0, obf.length() - 1).trim();
                    }
                    currentNamed = named;
                    currentObf = obf;
                    out.mapClass(obf, named);
                } else if (currentObf != null) {
                    String t = line.trim();
                    int arrow = t.indexOf("->");
                    if (arrow < 0) {
                        continue;
                    }
                    String left = t.substring(0, arrow).trim();
                    String obfName = t.substring(arrow + 2).trim();
                    // left = "void tick()" or "int fps" or "void tick(int,java.lang.String)"
                    int paren = left.indexOf('(');
                    if (paren >= 0) {
                        int endParen = left.indexOf(')', paren);
                        String beforeParen = left.substring(0, paren).trim();
                        String methodNamed = beforeParen.substring(beforeParen.lastIndexOf(' ') + 1);
                        String params = endParen > paren ? left.substring(paren + 1, endParen).trim() : "";
                        String desc = mojangParamsToDescriptor(params);
                        out.mapMethod(currentObf, obfName, desc, methodNamed);
                        // Also register a descriptor-agnostic entry (empty descriptor)
                        // so NameRemapper can match even when Vineflower reports
                        // a slightly different descriptor.
                        out.mapMethod(currentObf, obfName, "", methodNamed);
                    } else {
                        int sp = left.lastIndexOf(' ');
                        String fieldNamed = sp >= 0 ? left.substring(sp + 1).trim() : left;
                        String typeDesc = sp >= 0 ? left.substring(0, sp).trim() : "";
                        out.mapField(currentObf, obfName, mojangTypeToDescriptor(typeDesc), fieldNamed);
                        out.mapField(currentObf, obfName, "", fieldNamed);
                    }
                }
            }
        }
    }

    /** Best-effort conversion of Mojang param list to a descriptor suffix. */
    private static String mojangParamsToDescriptor(String params) {
        if (params.isEmpty()) {
            return "()V"; // return type unknown; remapper matches loosely
        }
        StringBuilder sb = new StringBuilder("(");
        for (String p : params.split(",")) {
            sb.append(mojangTypeToDescriptor(p.trim()));
        }
        sb.append(")V");
        return sb.toString();
    }

    static String mojangTypeToDescriptor(String type) {
        switch (type) {
            case "byte": return "B";
            case "char": return "C";
            case "double": return "D";
            case "float": return "F";
            case "int": return "I";
            case "long": return "J";
            case "short": return "S";
            case "boolean": return "Z";
            case "void": return "V";
            default: {
                if (type.endsWith("[]")) {
                    return "[" + mojangTypeToDescriptor(type.substring(0, type.length() - 2));
                }
                if (type.isEmpty()) {
                    return "";
                }
                return "L" + type.replace('.', '/') + ";";
            }
        }
    }

    /** Auto-detects Mojang format (contains "->" and class lines ending with ':'). */
    public static boolean looksLikeMojang(Path file) {
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int checked = 0;
            while ((line = br.readLine()) != null && checked < 50) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                checked++;
                if (!line.startsWith(" ") && line.contains("->") && line.trim().endsWith(":")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }
}
