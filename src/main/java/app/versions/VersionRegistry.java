package app.versions;

import app.core.GameVersion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Ordered profile registry. Built-ins cover all eras; extra profiles can be
 * contributed via {@link ServiceLoader} (plugin system, spec section 19).
 */
public final class VersionRegistry {

    private final List<VersionProfile> profiles = new ArrayList<>();

    public VersionRegistry() {
        profiles.add(new LegacyAlphaProfile());
        profiles.add(new BetaProfile());
        profiles.add(new LegacyReleaseProfile());
        profiles.add(new ModernReleaseProfile());
        profiles.add(new FutureProfile());
        // Plugin-provided profiles (optional, never required).
        try {
            ServiceLoader<VersionProfile> loader = ServiceLoader.load(VersionProfile.class);
            for (VersionProfile p : loader) {
                register(p);
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void register(VersionProfile profile) {
        // Custom profiles take precedence over the built-in fallback.
        profiles.add(0, profile);
    }

    public VersionProfile select(GameVersion version) {
        for (VersionProfile p : profiles) {
            try {
                if (p.handles(version)) {
                    return p;
                }
            } catch (Exception ignored) {
            }
        }
        return new FutureProfile();
    }

    public List<VersionProfile> all() {
        return Collections.unmodifiableList(profiles);
    }
}
