package app.bytecode;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * In-memory class dependency graph (spec section 10): inheritance,
 * interfaces, method/field/class references. Built incrementally so the
 * GUI can navigate without re-parsing bytecode.
 */
public final class ClassGraph {

    private final Map<String, BytecodeClass> classes = new TreeMap<>();
    private final Map<String, Set<String>> outgoing = new HashMap<>();
    private final Map<String, Set<String>> incoming = new HashMap<>();

    public void addClass(BytecodeClass bc) {
        classes.put(bc.getInternalName(), bc);
        Set<String> refs = new LinkedHashSet<>();
        if (bc.getSuperName() != null) {
            refs.add(bc.getSuperName());
        }
        refs.addAll(bc.getInterfaces());
        refs.addAll(bc.getReferencedClasses());
        refs.remove(bc.getInternalName());
        outgoing.put(bc.getInternalName(), refs);
        for (String r : refs) {
            incoming.computeIfAbsent(r, k -> new LinkedHashSet<>()).add(bc.getInternalName());
        }
        incoming.computeIfAbsent(bc.getInternalName(), k -> new LinkedHashSet<>());
    }

    public void addAll(Collection<BytecodeClass> all) {
        for (BytecodeClass bc : all) {
            addClass(bc);
        }
    }

    public BytecodeClass get(String internalName) {
        return classes.get(internalName);
    }

    public Set<String> getReferences(String internalName) {
        return Collections.unmodifiableSet(outgoing.getOrDefault(internalName, Set.of()));
    }

    public Set<String> getReferencedBy(String internalName) {
        return Collections.unmodifiableSet(incoming.getOrDefault(internalName, Set.of()));
    }

    /** Transitive closure of references (bounded to avoid blowup on huge graphs). */
    public Set<String> transitiveReferences(String internalName, int maxDepth) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(internalName);
        seen.add(internalName);
        int depth = 0;
        while (!queue.isEmpty() && depth < maxDepth) {
            int level = queue.size();
            for (int i = 0; i < level; i++) {
                String cur = queue.poll();
                for (String next : outgoing.getOrDefault(cur, Set.of())) {
                    if (seen.add(next) && classes.containsKey(next)) {
                        queue.add(next);
                    }
                }
            }
            depth++;
        }
        seen.remove(internalName);
        return seen;
    }

    /** Direct subclasses/implementors present in the analyzed set. */
    public Set<String> childrenOf(String internalName) {
        Set<String> out = new LinkedHashSet<>();
        for (BytecodeClass bc : classes.values()) {
            if (internalName.equals(bc.getSuperName()) || bc.getInterfaces().contains(internalName)) {
                out.add(bc.getInternalName());
            }
        }
        return out;
    }

    public List<String> superclassChain(String internalName) {
        List<String> chain = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cur = internalName;
        while (cur != null && seen.add(cur)) {
            BytecodeClass bc = classes.get(cur);
            if (bc == null || bc.getSuperName() == null) {
                break;
            }
            chain.add(bc.getSuperName());
            cur = bc.getSuperName();
        }
        return chain;
    }

    public int size() {
        return classes.size();
    }

    public Set<String> classNames() {
        return Collections.unmodifiableSet(classes.keySet());
    }
}
