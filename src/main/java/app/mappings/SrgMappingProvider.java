package app.mappings;

import app.core.GameVersion;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SRG / TSRG / XSRG family (MCP-era community mappings for legacy releases).
 * Supports:
 * <pre>
 * CL: obf named
 * FD: obfOwner/obfName namedOwner/namedName
 * MD: obfOwner/obfName desc namedOwner/namedName desc
 * // TSRG: obf named (+ tab-indented members)
 * </pre>
 */
public final class SrgMappingProvider implements MappingProvider {

    private final Path file;

    public SrgMappingProvider(Path file) {
        this.file = file;
    }

    @Override
    public String name() {
        return "srg";
    }

    @Override
    public MappingSet loadMappings(GameVersion version) throws IOException {
        MappingSet out = new MappingSet();
        out.putMetadata("provider", "srg");
        if (file == null || !Files.isRegularFile(file)) {
            out.putMetadata("note", "SRG mappings file not found: " + file);
            return out;
        }
        parseSrg(file, out);
        out.putMetadata("source", file.toString());
        return out;
    }

    static void parseSrg(Path file, MappingSet out) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            String currentObfClass = null;
            String currentNamedClass = null;
            while ((line = br.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("CL:")) {
                    String[] p = line.substring(3).trim().split("\\s+");
                    if (p.length >= 2) {
                        currentObfClass = p[0].replace('.', '/');
                        currentNamedClass = p[1].replace('.', '/');
                        out.mapClass(currentObfClass, currentNamedClass);
                    }
                } else if (line.startsWith("FD:")) {
                    String[] p = line.substring(3).trim().split("\\s+");
                    if (p.length >= 2) {
                        String obf = p[0];
                        String named = p[1];
                        int s = obf.lastIndexOf('/');
                        String owner = s >= 0 ? obf.substring(0, s) : (currentObfClass == null ? "" : currentObfClass);
                        String oname = s >= 0 ? obf.substring(s + 1) : obf;
                        int ns = named.lastIndexOf('/');
                        String nname = ns >= 0 ? named.substring(ns + 1) : named;
                        out.mapField(owner, oname, "", nname);
                    }
                } else if (line.startsWith("MD:")) {
                    String[] p = line.substring(3).trim().split("\\s+");
                    if (p.length >= 4) {
                        String obf = p[0];
                        String desc = p[1];
                        String named = p[2];
                        int s = obf.lastIndexOf('/');
                        String owner = s >= 0 ? obf.substring(0, s) : "";
                        String oname = s >= 0 ? obf.substring(s + 1) : obf;
                        int ns = named.lastIndexOf('/');
                        String nname = ns >= 0 ? named.substring(ns + 1) : named;
                        out.mapMethod(owner, oname, desc, nname);
                        out.mapMethod(owner, oname, "", nname);
                    }
                } else if (line.startsWith("\t") || line.startsWith("    ")) {
                    // TSRG member line: "<obfName> <namedName> [desc]"
                    String t = line.trim();
                    String[] p = t.split("\\s+");
                    if (p.length >= 2 && currentObfClass != null) {
                        if (p.length >= 3 || t.contains("(")) {
                            out.mapMethod(currentObfClass, p[0], p.length >= 3 ? p[2] : "", p[1]);
                            out.mapMethod(currentObfClass, p[0], "", p[1]);
                        } else {
                            out.mapField(currentObfClass, p[0], "", p[1]);
                        }
                    }
                } else {
                    // TSRG class line: "obf named"
                    String[] p = line.trim().split("\\s+");
                    if (p.length == 2 && !p[0].contains(":") && !p[0].contains("(")) {
                        currentObfClass = p[0].replace('.', '/');
                        currentNamedClass = p[1].replace('.', '/');
                        out.mapClass(currentObfClass, currentNamedClass);
                    }
                }
            }
        }
    }

    public static boolean looksLikeSrg(Path file) {
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int checked = 0;
            while ((line = br.readLine()) != null && checked < 30) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                checked++;
                String t = line.trim();
                if (t.startsWith("CL:") || t.startsWith("FD:") || t.startsWith("MD:")) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }
}
