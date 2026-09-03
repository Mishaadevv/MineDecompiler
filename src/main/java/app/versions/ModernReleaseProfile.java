package app.versions;

import app.core.GameVersion;

import java.util.Map;

/** 1.13+: Mojang ProGuard mappings, brigadier/datafixer/registry flattening. */
public final class ModernReleaseProfile implements VersionProfile {

    @Override
    public String id() {
        return "modern-release";
    }

    @Override
    public String label() {
        return "Modern Release (1.13+)";
    }

    @Override
    public boolean handles(GameVersion version) {
        return version.getEra() == GameVersion.Era.MODERN_RELEASE;
    }

    @Override
    public String preferredDecompiler() {
        return "vineflower";
    }

    @Override
    public String mappingsFormat() {
        return "mojang-proguard";
    }

    @Override
    public String mappingsHint() {
        return "Modern releases use official Mojang ProGuard mappings (client.txt/server.txt). "
                + "Downloaded automatically when missing (or copy them into <output>/mappings/ or pass --mappings <file|dir>).";
    }

    @Override
    public Map<String, String> decompilerOptions() {
        return Map.of("include-entire-classpath", "0");
    }

    @Override
    public String notes() {
        return "Modern releases: records/sealed classes/pattern matching need a modern decompiler (Vineflower). "
                + "Data-driven registries mean many classes are small and numerous.";
    }
}
