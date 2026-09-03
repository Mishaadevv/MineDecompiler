package app.plugins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Minimal plugin facade (spec section 19). Plugins are discovered via
 * {@link ServiceLoader}; the {@code plugins/} directory convention is
 * documented in README (drop a JAR on the classpath via -cp or the
 * launcher's plugin loader).
 */
public final class PluginManager {

    /** Marker interface for all mcdecompiler plugins. */
    public interface Plugin {
        String id();

        String description();
    }

    private PluginManager() {
    }

    public static <T> List<T> load(Class<T> type) {
        List<T> out = new ArrayList<>();
        try {
            for (T t : ServiceLoader.load(type)) {
                out.add(t);
            }
        } catch (Exception ignored) {
        }
        return Collections.unmodifiableList(out);
    }
}
