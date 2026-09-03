package app.core;

import app.util.JarUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-source automatic version detection (spec section 6):
 * manifest, embedded metadata, known classes/structures, package signatures,
 * version-specific patterns, plus user override.
 */
public final class VersionDetector {

    private static final Pattern VERSION_IN_NAME = Pattern.compile(
            "(alpha|beta|snapshot|pre|rc|release)?[^\\d]*(\\d+\\.\\d+(\\.\\d+)?([_-](pre|rc)\\d*)?)",
            Pattern.CASE_INSENSITIVE);

    private VersionDetector() {
    }

    public static VersionDetectionResult detect(Path jar, String userOverride) {
        if (userOverride != null && !userOverride.isBlank()) {
            GameVersion forced = GameVersion.classify(userOverride.trim());
            return new VersionDetectionResult(forced, 1.0,
                    List.of(forced.getId()), List.of("user override: " + userOverride), true);
        }
        List<String> evidence = new ArrayList<>();
        Set<String> candidates = new LinkedHashSet<>();
        double confidence = 0.0;
        String bestId = null;

        // 1. Manifest
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest mf = jf.getManifest();
            if (mf != null) {
                Attributes a = mf.getMainAttributes();
                for (String key : new String[]{"Implementation-Version", "Minecraft-Version",
                        "Bundle-Version", "Specification-Version"}) {
                    String v = a.getValue(key);
                    if (v != null && !v.isBlank()) {
                        evidence.add("manifest " + key + "=" + v);
                        candidates.add(v.trim());
                        if (bestId == null) {
                            bestId = v.trim();
                            confidence = Math.max(confidence, 0.7);
                        }
                    }
                }
            }
            // 2. Embedded metadata files (the manifest itself is handled above;
            // note: META-INF/MANIFEST.MF is deliberately NOT scanned for version
            // numbers — its "Manifest-Version: 1.0" is not a Minecraft version).
            String[] metaNames = {"version.json", "META-INF/version.json", "version.txt",
                    "pack.mcmeta", "assets/minecraft/version.json"};
            Enumeration<JarEntry> en = jf.entries();
            List<String> entries = new ArrayList<>();
            while (en.hasMoreElements()) {
                entries.add(en.nextElement().getName());
            }
            for (String name : metaNames) {
                if (entries.contains(name)) {
                    try {
                        byte[] data = JarUtils.readEntry(jar, name);
                        if (data != null && data.length < 200_000) {
                            String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                            String found = extractVersionString(text);
                            if (found != null) {
                                evidence.add(name + " suggests " + found);
                                candidates.add(found);
                                if (bestId == null) {
                                    bestId = found;
                                    confidence = Math.max(confidence,
                                            name.equals("version.json") ? 0.9 : 0.6);
                                }
                            } else if (name.equals("version.json")) {
                                evidence.add("version.json present (unparsed)");
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }

            // 3-5. Known classes / package signatures / version-specific patterns
            boolean hasModernNet = entries.stream().anyMatch(e -> e.startsWith("net/minecraft/"));
            boolean hasFlatObf = entries.stream().anyMatch(e -> e.matches("[a-z]{1,3}\\.class"));
            boolean hasMojangBrigadier = entries.stream().anyMatch(e -> e.startsWith("com/mojang/brigadier/"));
            boolean hasDatafixer = entries.stream().anyMatch(e -> e.startsWith("com/mojang/datafixers/"));
            boolean hasBlaze3d = entries.stream().anyMatch(e -> e.startsWith("com/mojang/blaze3d/"));
            boolean hasOldSound = entries.stream().anyMatch(e -> e.startsWith("paulscode/"));
            boolean hasLwjgl2 = entries.stream().anyMatch(e -> e.startsWith("org/lwjgl/LWJGLException.class"));

            if (entries.contains("net/minecraft/client/Minecraft.class")) {
                evidence.add("known class net/minecraft/client/Minecraft (named, modern-like)");
                if (bestId == null) {
                    confidence = Math.max(confidence, 0.4);
                }
            }
            if (entries.contains("net/minecraft/src/Minecraft.class") || entries.contains("net/minecraft/client/Minecraft.class") && hasLwjgl2) {
                evidence.add("legacy package layout detected");
            }
            if (entries.contains("Minecraft.class") || entries.contains("mi.class")) {
                evidence.add("flat root classes (alpha/beta style obfuscation)");
                if (bestId == null) {
                    bestId = hasOldSound ? "beta" : "alpha";
                    confidence = Math.max(confidence, 0.35);
                }
            }
            if (hasMojangBrigadier || hasDatafixer || hasBlaze3d) {
                evidence.add("modern Mojang libraries present (brigadier/datafixer/blaze3d) -> 1.13+");
                if (bestId == null) {
                    bestId = "modern";
                    confidence = Math.max(confidence, 0.45);
                }
            }
            if (hasModernNet && !hasMojangBrigadier) {
                evidence.add("net/minecraft packages without modern libs -> legacy release");
            }
            if (!hasModernNet && hasFlatObf) {
                evidence.add("single-letter package obfuscation -> legacy/alpha era");
            }
            // 6. Filename hints. Explicit alpha/beta markers (b1.7.3, a1.2.6,
            // "alpha"/"beta" words) are strong evidence and may override weak
            // structural guesses; bare numbers are weak and only fill gaps.
            String fileName = jar.getFileName().toString().toLowerCase(Locale.ROOT);
            Matcher ab = Pattern.compile("(?:^|[^a-z])([ab])(\\d+\\.\\d+(?:\\.\\d+)?(?:_\\d+)?)")
                    .matcher(fileName);
            if (ab.find()) {
                String guess = (ab.group(1).equals("b") ? "beta " : "alpha ") + ab.group(2);
                evidence.add("jar filename suggests " + guess);
                candidates.add(guess);
                if (0.55 > confidence) {
                    bestId = guess;
                    confidence = 0.55;
                }
            } else if (fileName.contains("alpha") || fileName.contains("beta")) {
                String guess = fileName.contains("alpha") ? "alpha" : "beta";
                evidence.add("jar filename mentions " + guess);
                candidates.add(guess);
                if (0.5 > confidence) {
                    bestId = guess;
                    confidence = 0.5;
                }
            }
            Matcher m = VERSION_IN_NAME.matcher(fileName);
            if (m.find()) {
                String guess = m.group(0).replaceAll("^[^\\d]+", "");
                evidence.add("jar filename suggests " + guess);
                candidates.add(guess);
                if (bestId == null) {
                    bestId = guess;
                    confidence = Math.max(confidence, 0.25);
                }
            }
        } catch (IOException e) {
            evidence.add("failed to read jar: " + e.getMessage());
        }

        if (bestId == null) {
            bestId = "unknown";
            evidence.add("no conclusive evidence; manual selection required");
        }
        GameVersion best = GameVersion.classify(bestId.equals("unknown") ? "unknown" : bestId);
        // Refine "modern"/"beta"/"alpha" placeholders into eras explicitly
        if (bestId.equals("modern")) {
            best = new GameVersion("unknown-modern", GameVersion.Era.MODERN_RELEASE,
                    GameVersion.Side.UNKNOWN, false);
        } else if (bestId.equals("beta") || bestId.equals("alpha")) {
            best = new GameVersion("unknown-" + bestId,
                    bestId.equals("alpha") ? GameVersion.Era.ALPHA : GameVersion.Era.BETA,
                    GameVersion.Side.UNKNOWN, false);
        }
        if (candidates.isEmpty()) {
            candidates.add(best.getId());
        }
        return new VersionDetectionResult(best, confidence, new ArrayList<>(candidates), evidence, false);
    }

    static String extractVersionString(String text) {
        // Try JSON "id": "1.20.1" / "name": "..." then generic number patterns.
        Pattern json = Pattern.compile("\"(id|name|version)\"\\s*:\\s*\"([^\"]+)\"");
        Matcher jm = json.matcher(text);
        while (jm.find()) {
            String v = jm.group(2);
            if (v.matches(".*\\d+\\.\\d+.*") || v.toLowerCase(Locale.ROOT).contains("alpha")
                    || v.toLowerCase(Locale.ROOT).contains("beta")) {
                return v.trim();
            }
        }
        Matcher m = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?").matcher(text);
        if (m.find()) {
            return m.group(0);
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("alpha")) return "alpha";
        if (lower.contains("beta")) return "beta";
        return null;
    }
}
