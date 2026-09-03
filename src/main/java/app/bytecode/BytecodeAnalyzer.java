package app.bytecode;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * ASM-based bytecode analysis layer (spec section 11).
 * Streaming: parses class-by-class, never holds all bytecode at once.
 */
public final class BytecodeAnalyzer {

    private BytecodeAnalyzer() {
    }

    public static BytecodeClass parse(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        Collector collector = new Collector();
        reader.accept(collector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return collector.toBytecodeClass();
    }

    /** Parses every class in the JAR; caller decides how much to retain. */
    public static Map<String, BytecodeClass> analyzeJar(Path jar) throws IOException {
        Map<String, BytecodeClass> out = new LinkedHashMap<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")
                        || e.getName().startsWith("META-INF/")) {
                    continue;
                }
                try (InputStream in = jf.getInputStream(e)) {
                    byte[] bytes = in.readAllBytes();
                    try {
                        BytecodeClass bc = parse(bytes);
                        out.put(bc.getInternalName(), bc);
                    } catch (Exception ex) {
                        // Record an "unknown" placeholder so no class is silently lost.
                        String internal = e.getName().substring(0, e.getName().length() - 6);
                        out.put(internal, new BytecodeClass(internal, "java/lang/Object",
                                List.of(), 0, 0, List.of(), List.of(), Set.of(), List.of()));
                    }
                }
            }
        }
        return out;
    }

    /** Counts classes without retaining them (cheap pre-pass for progress bars). */
    public static int countClasses(Path jar) throws IOException {
        int n = 0;
        try (JarFile jf = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (!e.isDirectory() && e.getName().endsWith(".class")
                        && !e.getName().startsWith("META-INF/")) {
                    n++;
                }
            }
        }
        return n;
    }

    private static final class Collector extends ClassVisitor {
        String name;
        String superName;
        List<String> interfaces = new ArrayList<>();
        int access;
        int version;
        List<BytecodeMethod> methods = new ArrayList<>();
        List<BytecodeField> fields = new ArrayList<>();
        Set<String> referenced = new LinkedHashSet<>();
        List<String> annotations = new ArrayList<>();

        Collector() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            this.version = version;
            this.access = access;
            this.name = name;
            this.superName = superName;
            if (interfaces != null) {
                for (String i : interfaces) {
                    this.interfaces.add(i);
                    referenced.add(i);
                }
            }
            if (superName != null) {
                referenced.add(superName);
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            annotations.add(descriptor);
            addTypeRefs(descriptor);
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            fields.add(new BytecodeField(name, descriptor, access, value));
            addTypeRefs(descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            methods.add(new BytecodeMethod(name, descriptor, access, signature));
            addTypeRefs(descriptor);
            if (exceptions != null) {
                for (String e : exceptions) {
                    referenced.add(e);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String mname,
                                            String mdesc, boolean itf) {
                    referenced.add(owner);
                    addTypeRefs(mdesc);
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
                    referenced.add(owner);
                    addTypeRefs(fdesc);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    addTypeRefs(type);
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof org.objectweb.asm.Type) {
                        addTypeRefs(((org.objectweb.asm.Type) value).getDescriptor());
                    }
                }
            };
        }

        void addTypeRefs(String descriptor) {
            if (descriptor == null) {
                return;
            }
            // Extract every L...; reference from descriptors/signatures.
            int i = 0;
            while ((i = descriptor.indexOf('L', i)) != -1) {
                int end = descriptor.indexOf(';', i);
                if (end == -1) {
                    break;
                }
                String internal = descriptor.substring(i + 1, end);
                if (!internal.isEmpty() && internal.matches("[\\w$/]+")) {
                    referenced.add(internal);
                }
                i = end + 1;
            }
        }

        BytecodeClass toBytecodeClass() {
            // Drop self-reference and primitives noise.
            referenced.remove(name);
            referenced.removeIf(r -> r == null || r.isEmpty() || !r.contains("/"));
            return new BytecodeClass(name, superName, interfaces, access, version,
                    methods, fields, referenced, annotations);
        }
    }

    /** Human-readable access flags, e.g. "public final". */
    public static String accessToString(int access) {
        StringBuilder sb = new StringBuilder();
        if ((access & Opcodes.ACC_PUBLIC) != 0) sb.append("public ");
        if ((access & Opcodes.ACC_PRIVATE) != 0) sb.append("private ");
        if ((access & Opcodes.ACC_PROTECTED) != 0) sb.append("protected ");
        if ((access & Opcodes.ACC_STATIC) != 0) sb.append("static ");
        if ((access & Opcodes.ACC_FINAL) != 0) sb.append("final ");
        if ((access & Opcodes.ACC_ABSTRACT) != 0) sb.append("abstract ");
        if ((access & Opcodes.ACC_SYNTHETIC) != 0) sb.append("synthetic ");
        if ((access & Opcodes.ACC_BRIDGE) != 0) sb.append("bridge ");
        return sb.toString().trim();
    }

    public static String descriptorToJavaType(String descriptor) {
        try {
            org.objectweb.asm.Type t = org.objectweb.asm.Type.getType(descriptor);
            return t.getClassName();
        } catch (Exception e) {
            return descriptor;
        }
    }
}
