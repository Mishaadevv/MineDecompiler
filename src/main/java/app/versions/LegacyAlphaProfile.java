package app.versions;

import app.core.GameVersion;

import java.util.Map;

/** Alpha: flat root packages, heavy obfuscation, no official mappings. */
public final class LegacyAlphaProfile implements VersionProfile {

    @Override
    public String id() {
        return "legacy-alpha";
    }

    @Override
    public String label() {
        return "Legacy Alpha";
    }

    @Override
    public boolean handles(GameVersion version) {
        return version.getEra() == GameVersion.Era.ALPHA;
    }

    @Override
    public String mappingsFormat() {
        return "none/community-tiny";
    }

    @Override
    public String mappingsHint() {
        return "Alpha has no Mojang mappings. Drop community Tiny (*.tiny) files into <output>/mappings/ or pass --mappings. "
                + "Without them, readable synthetic names are generated.";
    }

    @Override
    public Map<String, String> decompilerOptions() {
        return Map.of("ascii-string-characters", "1", "decompile-generics", "1");
    }

    @Override
    public String notes() {
        return "Alpha-era: classes live in the default package or single-letter packages; "
                + "static initializers and magic constants are common; anonymous classes are frequent.";
    }
}
