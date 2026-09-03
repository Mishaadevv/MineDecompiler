package app.mappings;

import app.core.GameVersion;

/** Fallback provider: always returns an empty set (decompile still works). */
public final class EmptyMappingProvider implements MappingProvider {

    @Override
    public String name() {
        return "empty";
    }

    @Override
    public MappingSet loadMappings(GameVersion version) {
        MappingSet set = new MappingSet();
        set.putMetadata("provider", "empty");
        set.putMetadata("note", "No mappings available; synthetic names will be used.");
        return set;
    }
}
