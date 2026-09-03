package app.versions;

import app.core.GameVersion;

import java.util.Map;

/** 1.0–1.12: SRG/MCP community mappings, net.minecraft.* packages emerge. */
public final class LegacyReleaseProfile implements VersionProfile {

    @Override
    public String id() {
        return "legacy-release";
    }

    @Override
    public String label() {
        return "Legacy Release (1.0–1.12)";
    }

    @Override
    public boolean handles(GameVersion version) {
        return version.getEra() == GameVersion.Era.LEGACY_RELEASE;
    }

    @Override
    public String mappingsFormat() {
        return "srg/tsrg/mcp";
    }

    @Override
    public String mappingsHint() {
        return "Legacy releases use community SRG/TSRG/MCP mappings. Place them in <output>/mappings/ or pass --mappings.";
    }

    @Override
    public Map<String, String> decompilerOptions() {
        return Map.of();
    }

    @Override
    public String notes() {
        return "Legacy releases: net/minecraft package tree; inner/anonymous classes common; "
                + "pre-flattening block/item IDs (numeric) are preserved as-is.";
    }
}
