package app.decompiler;

import app.core.GameVersion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Engine registry (spec section 4): Vineflower + CFR-compat + javap fallback.
 * Extra engines can be contributed via {@link ServiceLoader} (plugins).
 */
public final class DecompilerRegistry {

    private final Map<String, DecompilerEngine> engines = new LinkedHashMap<>();

    public DecompilerRegistry() {
        register(new VineflowerEngine());
        register(new CfrCompatEngine());
        register(new JavapFallbackEngine());
        try {
            for (DecompilerEngine e : ServiceLoader.load(DecompilerEngine.class)) {
                register(e);
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void register(DecompilerEngine engine) {
        engines.put(engine.name().toLowerCase(), engine);
    }

    public DecompilerEngine byName(String name) {
        if (name == null || name.isBlank() || name.equalsIgnoreCase("auto")) {
            return auto(null);
        }
        DecompilerEngine e = engines.get(name.toLowerCase());
        if (e == null) {
            throw new IllegalArgumentException("Unknown decompiler: " + name
                    + ". Available: " + availableNames());
        }
        return e;
    }

    /** Auto-selects the best engine for the version (Vineflower first). */
    public DecompilerEngine auto(GameVersion version) {
        DecompilerEngine vf = engines.get("vineflower");
        if (vf != null && vf.isAvailable()) {
            return vf;
        }
        DecompilerEngine cfr = engines.get("cfr");
        if (cfr != null && cfr.isAvailable()) {
            return cfr;
        }
        return engines.get("javap");
    }

    public List<String> availableNames() {
        List<String> out = new ArrayList<>();
        for (DecompilerEngine e : engines.values()) {
            if (e.isAvailable()) {
                out.add(e.name());
            }
        }
        return Collections.unmodifiableList(out);
    }

    public List<DecompilerEngine> all() {
        return List.copyOf(engines.values());
    }
}
