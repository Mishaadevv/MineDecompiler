package app.versions;

import app.core.GameVersion;

/** Forward-compatible fallback for unknown/future versions: decompile everything, rename nothing. */
public final class FutureProfile implements VersionProfile {

    @Override
    public String id() {
        return "future";
    }

    @Override
    public String label() {
        return "Future / Unknown (best-effort)";
    }

    @Override
    public boolean handles(GameVersion version) {
        return version.getEra() == GameVersion.Era.FUTURE
                || version.getEra() == GameVersion.Era.UNKNOWN;
    }

    @Override
    public String mappingsFormat() {
        return "any/none";
    }

    @Override
    public String notes() {
        return "Unknown version: best-effort pipeline. Any recognizable mapping format in <output>/mappings/ is applied; "
                + "everything else keeps synthetic names with UNMAPPED markers.";
    }
}
