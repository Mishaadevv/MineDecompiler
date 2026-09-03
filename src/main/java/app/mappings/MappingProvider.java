package app.mappings;

import app.core.GameVersion;

/**
 * Mapping abstraction (spec section 5). Implementations are UI-independent
 * and must never throw just because mappings are missing — they return
 * an empty MappingSet instead.
 */
public interface MappingProvider {

    /** Human-readable id, e.g. "mojang", "tiny", "srg", "custom", "empty". */
    String name();

    /**
     * Loads mappings for the given version. Returns an empty set when
     * nothing is available — never null, never throws for missing data.
     */
    MappingSet loadMappings(GameVersion version) throws Exception;

    /** Whether this provider can plausibly serve the given version. */
    default boolean supports(GameVersion version) {
        return true;
    }
}
