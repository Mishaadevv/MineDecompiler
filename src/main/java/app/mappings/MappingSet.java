package app.mappings;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unified internal mapping format (spec section 5):
 * classes / methods / fields / parameters + metadata.
 * Keys use JVM internal names and "owner + name + descriptor" triples.
 */
public final class MappingSet {

    private final Map<String, String> classes = new LinkedHashMap<>();
    private final Map<String, String> methods = new LinkedHashMap<>();
    private final Map<String, String> fields = new LinkedHashMap<>();
    private final Map<String, String> parameters = new LinkedHashMap<>();
    private final Map<String, String> metadata = new LinkedHashMap<>();

    public void mapClass(String obfInternal, String namedInternal) {
        if (!isBlank(obfInternal) && !isBlank(namedInternal)) {
            classes.put(obfInternal, namedInternal);
        }
    }

    public void mapMethod(String obfOwner, String obfName, String descriptor, String named) {
        if (!isBlank(obfOwner) && !isBlank(obfName) && !isBlank(named)) {
            // Keyed by owner+name only (descriptors intentionally ignored):
            // obfuscated overloads virtually always share one name, and this
            // halves the entry count for the remap hot loop.
            methods.put(key(obfOwner, obfName, descriptor), named);
        }
    }

    public void mapField(String obfOwner, String obfName, String descriptor, String named) {
        if (!isBlank(obfOwner) && !isBlank(obfName) && !isBlank(named)) {
            fields.put(key(obfOwner, obfName, descriptor), named);
        }
    }

    public void mapParameter(String methodKey, String named) {
        if (!isBlank(methodKey) && !isBlank(named)) {
            parameters.put(methodKey, named);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public void putMetadata(String k, String v) {
        metadata.put(k, v);
    }

    /** Owner+name key; descriptors are ignored (see {@link #mapMethod}). */
    public static String key(String owner, String name, String descriptor) {
        return owner + "|" + name;
    }

    public String mapClassName(String obfInternal) {
        return classes.getOrDefault(obfInternal, obfInternal);
    }

    public boolean hasClass(String obfInternal) {
        return classes.containsKey(obfInternal);
    }

    public String mapMethodName(String owner, String name, String descriptor) {
        return methods.getOrDefault(key(owner, name, descriptor), name);
    }

    public String mapFieldName(String owner, String name, String descriptor) {
        return fields.getOrDefault(key(owner, name, descriptor), name);
    }

    public Map<String, String> getClasses() {
        return Collections.unmodifiableMap(classes);
    }

    public Map<String, String> getMethods() {
        return Collections.unmodifiableMap(methods);
    }

    public Map<String, String> getFields() {
        return Collections.unmodifiableMap(fields);
    }

    public Map<String, String> getParameters() {
        return Collections.unmodifiableMap(parameters);
    }

    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public int size() {
        return classes.size() + methods.size() + fields.size();
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public void putAll(MappingSet other) {
        classes.putAll(other.classes);
        methods.putAll(other.methods);
        fields.putAll(other.fields);
        parameters.putAll(other.parameters);
        metadata.putAll(other.metadata);
    }
}
