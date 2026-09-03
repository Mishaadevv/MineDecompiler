package app.versions;

import app.core.GameVersion;

import java.util.Map;

/** Beta: paulscode sounds, LWJGL2, flat-ish obfuscation, MCP-style community mappings. */
public final class BetaProfile implements VersionProfile {

    @Override
    public String id() {
        return "beta";
    }

    @Override
    public String label() {
        return "Beta";
    }

    @Override
    public boolean handles(GameVersion version) {
        return version.getEra() == GameVersion.Era.BETA;
    }

    @Override
    public String mappingsFormat() {
        return "srg/tiny/community";
    }

    @Override
    public String mappingsHint() {
        return "Beta has no Mojang mappings. Community SRG/TSRG or Tiny files (e.g. OrnithoMC) are picked up from <output>/mappings/ or --mappings.";
    }

    @Override
    public Map<String, String> decompilerOptions() {
        return Map.of("ascii-string-characters", "1");
    }

    @Override
    public String notes() {
        return "Beta-era: org/lwjgl + paulscode dependencies are external libraries (not decompiled); "
                + "net/minecraft/src layout appears in some builds.";
    }
}
