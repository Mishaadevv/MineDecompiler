package app.core;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable description of a Minecraft version.
 *
 * <p>Era classification drives profile selection:
 * ALPHA/BETA have no Mojang mappings and flat packages;
 * LEGACY_RELEASE covers old obfuscation without official mappings;
 * MODERN covers Mojang-mapped releases; FUTURE is a forward-compatible fallback.
 */
public final class GameVersion {

    public enum Era {
        ALPHA, BETA, LEGACY_RELEASE, MODERN_RELEASE, FUTURE, UNKNOWN
    }

    public enum Side {
        CLIENT, SERVER, UNKNOWN
    }

    private final String id;
    private final Era era;
    private final Side side;
    private final boolean snapshot;

    public GameVersion(String id, Era era, Side side, boolean snapshot) {
        this.id = Objects.requireNonNull(id, "id");
        this.era = era == null ? Era.UNKNOWN : era;
        this.side = side == null ? Side.UNKNOWN : side;
        this.snapshot = snapshot;
    }

    public String getId() {
        return id;
    }

    public Era getEra() {
        return era;
    }

    public Side getSide() {
        return side;
    }

    public boolean isSnapshot() {
        return snapshot;
    }

    public boolean isObfuscated() {
        return true;
    }

    /**
     * Heuristic era classification from a raw version id string.
     * Never throws; returns UNKNOWN era when nothing matches.
     */
    public static GameVersion classify(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return new GameVersion("unknown", Era.UNKNOWN, Side.UNKNOWN, false);
        }
        String id = rawId.trim();
        String lower = id.toLowerCase(Locale.ROOT);
        boolean snapshot = lower.contains("snapshot") || lower.matches(".*\\d{2}w\\d{2}[a-z]?.*")
                || lower.contains("pre-release") || lower.contains("pre") && lower.contains("rc");

        Era era;
        if (lower.contains("alpha") || lower.matches("(?i).*[^a-z]a\\d+\\.\\d+.*")
                || lower.matches("(?i)^a\\d+\\.\\d+.*")) {
            era = Era.ALPHA;
        } else if (lower.contains("beta") || lower.matches("(?i).*[^a-z]b\\d+\\.\\d+.*")
                || lower.matches("(?i)^b\\d+\\.\\d+.*")) {
            era = Era.BETA;
        } else if (lower.matches(".*\\b1\\.(\\d+).*")) {
            // Numeric 1.x line: 1.0–1.12 are legacy, 1.13+ modern.
            // Parsed numerically so 1.20 is NOT mistaken for 1.2.
            int minor = 13;
            try {
                java.util.regex.Matcher mm =
                        java.util.regex.Pattern.compile("\\b1\\.(\\d+)").matcher(lower);
                if (mm.find()) {
                    minor = Integer.parseInt(mm.group(1));
                }
            } catch (NumberFormatException ignored) {
            }
            era = minor <= 12 ? Era.LEGACY_RELEASE : Era.MODERN_RELEASE;
        } else if (lower.matches(".*\\b[2-9]\\d*\\.\\d+.*")) {
            era = Era.MODERN_RELEASE;
        } else if (lower.matches(".*\\d+\\.\\d+(\\.\\d+)?.*")) {
            era = Era.MODERN_RELEASE;
        } else {
            era = Era.FUTURE;
        }
        return new GameVersion(id, era, Side.UNKNOWN, snapshot);
    }

    @Override
    public String toString() {
        return id + " [" + era + "]";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GameVersion)) return false;
        GameVersion that = (GameVersion) o;
        return snapshot == that.snapshot && Objects.equals(id, that.id)
                && era == that.era && side == that.side;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, era, side, snapshot);
    }
}
