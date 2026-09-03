package app.reconstruction;

import app.mappings.MappingSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies class-name mappings to decompiled sources (spec: Name Remapping stage).
 * Strategy is text-based but token-aware: replaces obfuscated internal/binary
 * names with mapped ones for the class itself, its package, imports, and
 * fully-qualified references. Member-level renames are applied conservatively
 * (whole-word) and counted for the final report.
 */
public final class NameRemapper {

    private NameRemapper() {
    }

    public static final class Result {
        public final Map<String, String> sourcesByNewInternal;
        public final int classesRenamed;
        public final int memberHits;
        /** New internal names whose class mapping actually fired (for UNMAPPED banners). */
        public final java.util.Set<String> renamedTargets;

        Result(Map<String, String> sourcesByNewInternal, int classesRenamed, int memberHits,
               java.util.Set<String> renamedTargets) {
            this.sourcesByNewInternal = sourcesByNewInternal;
            this.classesRenamed = classesRenamed;
            this.memberHits = memberHits;
            this.renamedTargets = renamedTargets;
        }
    }

    public static Result remap(Map<String, String> sources, MappingSet mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return new Result(new LinkedHashMap<>(sources), 0, 0, java.util.Set.of());
        }
        // Precompile every rule ONCE (longest obf names first to avoid a-vs-ab
        // prefix collisions); the per-file loop then only scans and substitutes.
        List<ClassRule> classRules = new ArrayList<>();
        List<Map.Entry<String, String>> classEntries =
                new ArrayList<>(mappings.getClasses().entrySet());
        classEntries.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        for (Map.Entry<String, String> c : classEntries) {
            if (!c.getKey().equals(c.getValue())) {
                classRules.add(new ClassRule(c.getKey(), c.getValue()));
            }
        }
        List<MemberRule> methodRules = MemberRule.of(mappings.getMethods(), true);
        List<MemberRule> fieldRules = MemberRule.of(mappings.getFields(), false);

        Map<String, String> out = new LinkedHashMap<>();
        java.util.Set<String> renamedTargets = new java.util.LinkedHashSet<>();
        int renamed = 0;
        int memberHits = 0;

        for (Map.Entry<String, String> e : sources.entrySet()) {
            String obfInternal = e.getKey();
            String src = e.getValue();
            String newInternal = mappings.mapClassName(obfInternal);
            if (!newInternal.equals(obfInternal)) {
                renamed++;
                renamedTargets.add(newInternal);
            }
            // 1. Fix package + top-level declaration FIRST (simple names), so the
            //    global binary-name replacement below never produces
            //    "class com.foo.Bar" (illegal Java) at the declaration site.
            String transformed = fixPackageAndClassDecl(src, obfInternal, newInternal);
            String newSimple = newInternal.substring(newInternal.lastIndexOf('/') + 1).split("\\$")[0];
            // 2. Members BEFORE classes: field/method slots are claimed first, so
            //    a short class name (e.g. "a") can no longer clobber a member
            //    declaration. Rules only fire when their owner class is actually
            //    referenced in this file (plus always for the file's own class),
            //    which also stops cross-owner pollution of reused one-letter names.
            for (MemberRule r : fieldRules) {
                if (r.appliesTo(obfInternal, transformed)) {
                    String next = r.pattern.matcher(transformed)
                            .replaceAll(Matcher.quoteReplacement(r.named));
                    if (!next.equals(transformed)) {
                        memberHits++;
                        transformed = next;
                    }
                }
            }
            for (MemberRule r : methodRules) {
                if (r.appliesTo(obfInternal, transformed)) {
                    String next = r.pattern.matcher(transformed)
                            .replaceAll(Matcher.quoteReplacement(r.named));
                    if (!next.equals(transformed)) {
                        memberHits++;
                        transformed = next;
                    }
                }
            }
            // 3. Classes last, with member-slot awareness (see applyClassRule).
            for (ClassRule r : classRules) {
                if (!transformed.contains(r.obfBinary) && !transformed.contains(r.obf)
                        && !transformed.contains(r.obfPath)) {
                    continue;
                }
                if (r.obf.equals(obfInternal)) {
                    // Own class: declaration/constructors are already fixed to the
                    // simple name above — remaining references use it too (same
                    // package after the move), never the fully-qualified name.
                    transformed = applyClassRule(transformed, r.binaryPat, newSimple, r.shortName);
                } else {
                    transformed = applyClassRule(transformed, r.binaryPat, r.namedBinary, r.shortName);
                }
                transformed = applyClassRule(transformed, r.pathPat, r.namedPath, r.shortName);
            }
            out.put(newInternal, transformed);
        }
        return new Result(out, renamed, memberHits, java.util.Collections.unmodifiableSet(renamedTargets));
    }

    private static boolean isPlausibleMemberName(String s) {
        // Avoid renaming single letters aggressively inside words; handled by token regex.
        return s.matches("[A-Za-z_$][A-Za-z0-9_$]*");
    }

    /** Precompiled class rule: boundary-safe binary + path patterns. */
    private static final class ClassRule {
        final String obf;
        final String obfBinary;
        final String namedBinary;
        final String obfPath;
        final String namedPath;
        final boolean shortName;
        final Pattern binaryPat;
        final Pattern pathPat;

        ClassRule(String obf, String named) {
            this.obf = obf;
            this.obfBinary = obf.replace('/', '.').replace('$', '.');
            this.namedBinary = named.replace('/', '.').replace('$', '.');
            this.obfPath = obf.replace('.', '/');
            this.namedPath = named.replace('.', '/');
            this.shortName = obfBinary.length() <= 2;
            this.binaryPat = Pattern.compile("(?<![\\w$.])" + Pattern.quote(obfBinary));
            this.pathPat = Pattern.compile("(?<![\\w$.])" + Pattern.quote(obfPath));
        }
    }

    /**
     * Context-aware class substitution. A bare token match is NOT enough for
     * short obfuscated names: member slots are skipped so {@code int a;} or
     * {@code a()} are never treated as the class {@code a}. Replaced contexts:
     * type references, {@code new}/{@code extends}/{@code instanceof}/casts —
     * anything else (member slots, longer identifiers sharing the prefix)
     * is left untouched.
     */
    static String applyClassRule(String src, Pattern tokPat, String replacement, boolean shortName) {
        Matcher m = tokPat.matcher(src);
        StringBuffer sb = null;
        while (m.find()) {
            int end = m.end();
            char next = end < src.length() ? src.charAt(end) : 0;
            // Longer identifier sharing the prefix (pattern has no trailing guard).
            if (next != 0 && (next == '_' || next == '$' || Character.isLetterOrDigit(next))) {
                continue;
            }
            boolean memberSlot = next == ';' || next == '='
                    || (shortName && next == '.');
            boolean call = next == '(';
            boolean ctorCall = false;
            int s = m.start();
            String before = src.substring(Math.max(0, s - 16), s);
            // Imports always name types, even though they end with ';'.
            boolean isImport = before.matches("(?s).*\\bimport\\s+(static\\s+)?$");
            if (call) {
                String justBefore = src.substring(Math.max(0, s - 4), s);
                ctorCall = justBefore.endsWith("new ") || justBefore.endsWith("new\t")
                        || justBefore.endsWith("new\n") || justBefore.endsWith("new\r");
            }
            if ((memberSlot && !isImport) || (call && !ctorCall)) {
                continue;
            }
            if (sb == null) {
                sb = new StringBuffer(src.length() + 64);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        if (sb == null) {
            return src;
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Precompiled member rule (method or field context). */
    private static final class MemberRule {
        final String owner;
        final String ownerDotted;
        final String obf;
        final String named;
        final Pattern pattern;

        MemberRule(String owner, String obf, String named, boolean method) {
            this.owner = owner;
            this.ownerDotted = owner.replace('/', '.');
            this.obf = obf;
            this.named = named;
            this.pattern = method
                    // Method call/declaration only — and never a constructor call.
                    ? Pattern.compile("(?<=[\\s.(,;!])(?<!new )(?<!new\t)(?<!new\n)(?<!new\r)"
                            + Pattern.quote(obf) + "(?=\\s*\\()")
                    : Pattern.compile("(?<=[\\s.(,;])" + Pattern.quote(obf)
                            + "(?!\\s*\\()(?=[\\s;,)=+\\-\\[\\].])");
        }

        /**
         * Owner gate: a member rule fires only when its owner class is actually
         * referenced in this file (or is the file's own class, or is unknown).
         * Obfuscated jars reuse one-letter member names across hundreds of
         * classes — without this, every {@code a} would end up with one
         * arbitrary owner's name.
         */
        boolean appliesTo(String fileObfInternal, String src) {
            return owner.isEmpty() || owner.equals(fileObfInternal)
                    || src.contains(owner) || src.contains(ownerDotted);
        }

        static List<MemberRule> of(Map<String, String> entries, boolean method) {
            List<MemberRule> out = new ArrayList<>();
            for (Map.Entry<String, String> e : entries.entrySet()) {
                String[] parts = e.getKey().split("\\|", -1);
                if (parts.length < 2) {
                    continue;
                }
                String owner = parts[0];
                String obfMember = parts[1];
                String named = e.getValue();
                if (obfMember.equals(named) || obfMember.isEmpty()
                        || obfMember.equals("<init>") || obfMember.equals("<clinit>")
                        || !isPlausibleMemberName(obfMember)) {
                    continue;
                }
                out.add(new MemberRule(owner, obfMember, named, method));
            }
            // Longer names first for the same prefix-collision reason as classes.
            out.sort((a, b) -> Integer.compare(b.obf.length(), a.obf.length()));
            return out;
        }

        static List<MemberRule> of(Map<String, String> entries) {
            return of(entries, true);
        }
    }

    static String fixPackageAndClassDecl(String src, String obfInternal, String newInternal) {
        String newPkg = "";
        int slash = newInternal.lastIndexOf('/');
        if (slash > 0) {
            newPkg = newInternal.substring(0, slash).replace('/', '.');
        }
        String newSimple = newInternal.substring(newInternal.lastIndexOf('/') + 1).split("\\$")[0];
        String out = src;
        if (!newPkg.isEmpty()) {
            if (out.matches("(?s)^\\s*package\\s+[\\w.]+\\s*;.*")) {
                out = out.replaceFirst("(?s)^\\s*package\\s+[\\w.]+\\s*;",
                        Matcher.quoteReplacement("package " + newPkg + ";"));
            } else {
                out = "package " + newPkg + ";\n\n" + out;
            }
        }
        // If the file declares the obfuscated top-level class name, rename the declaration.
        String obfSimple = obfInternal.substring(obfInternal.lastIndexOf('/') + 1).split("\\$")[0];
        if (!obfSimple.equals(newSimple) && obfSimple.matches("[A-Za-z_$][A-Za-z0-9_$]*")
                && newSimple.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            out = out.replaceAll("(?<=(class|interface|enum|record)\\s)" + Pattern.quote(obfSimple)
                    + "(?=[\\s<{])", Matcher.quoteReplacement(newSimple));
            // constructors
            out = out.replaceAll("(?<![\\w$])" + Pattern.quote(obfSimple) + "(?=\\s*\\()",
                    Matcher.quoteReplacement(newSimple));
        }
        return out;
    }
}
