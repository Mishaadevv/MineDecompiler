package app.mappings;

import app.core.GameVersion;

import java.util.ArrayList;
import java.util.List;

/** Tries several providers in order and merges their results. */
public final class CompositeMappingProvider implements MappingProvider {

    private final List<MappingProvider> delegates = new ArrayList<>();

    public CompositeMappingProvider(MappingProvider... providers) {
        for (MappingProvider p : providers) {
            if (p != null) {
                delegates.add(p);
            }
        }
    }

    @Override
    public String name() {
        return "composite";
    }

    @Override
    public MappingSet loadMappings(GameVersion version) throws Exception {
        MappingSet out = new MappingSet();
        for (MappingProvider p : delegates) {
            try {
                if (p.supports(version)) {
                    out.putAll(p.loadMappings(version));
                }
            } catch (Exception e) {
                out.putMetadata("provider-error:" + p.name(), e.getMessage());
            }
        }
        return out;
    }
}
