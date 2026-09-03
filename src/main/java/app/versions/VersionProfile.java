package app.versions;

import app.core.GameVersion;

import java.util.Map;

/**
 * Per-era behavior description (spec section 2). Profiles are stateless
 * singletons selected by {@link VersionRegistry} — no giant if/else.
 */
public interface VersionProfile {

    /** Stable id, e.g. "modern-release". */
    String id();

    /** Human-readable label for the GUI/CLI. */
    String label();

    /** Whether this profile handles the given detected version. */
    boolean handles(GameVersion version);

    /** Preferred decompiler for Auto mode (registry name). */
    default String preferredDecompiler() {
        return "vineflower";
    }

    /** Expected mappings format hint for the UI. */
    default String mappingsFormat() {
        return "none";
    }

    /** Where to look for mappings (displayed to the user). */
    default String mappingsHint() {
        return "Place mapping files into <output>/mappings/ or pass --mappings.";
    }

    /** Extra Vineflower/fernflower options for this era. */
    default Map<String, String> decompilerOptions() {
        return Map.of();
    }

    /** Post-processing tweaks applied by the reconstruction pipeline. */
    default PostProcessing postProcessing() {
        return PostProcessing.defaults();
    }

    /** Notes about obfuscation/package quirks shown in reports. */
    default String notes() {
        return "";
    }

    /** Post-processing flags value object. */
    record PostProcessing(
            boolean stripSyntheticBridgeMarkers,
            boolean cleanupLoopsAndSwitches,
            boolean recoverEnums,
            boolean addUnmappedMarkers) {

        public static PostProcessing defaults() {
            return new PostProcessing(true, true, true, true);
        }
    }
}
