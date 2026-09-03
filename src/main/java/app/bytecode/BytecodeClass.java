package app.bytecode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable view of one class file: identity, hierarchy, members, references. */
public final class BytecodeClass {

    private final String internalName;
    private final String superName;
    private final List<String> interfaces;
    private final int access;
    private final int classFileVersion;
    private final List<BytecodeMethod> methods;
    private final List<BytecodeField> fields;
    private final Set<String> referencedClasses;
    private final List<String> annotations;

    public BytecodeClass(String internalName, String superName, List<String> interfaces,
                         int access, int classFileVersion,
                         List<BytecodeMethod> methods, List<BytecodeField> fields,
                         Set<String> referencedClasses, List<String> annotations) {
        this.internalName = internalName;
        this.superName = superName;
        this.interfaces = Collections.unmodifiableList(new ArrayList<>(interfaces));
        this.access = access;
        this.classFileVersion = classFileVersion;
        this.methods = Collections.unmodifiableList(new ArrayList<>(methods));
        this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
        this.referencedClasses = Collections.unmodifiableSet(new LinkedHashSet<>(referencedClasses));
        this.annotations = Collections.unmodifiableList(new ArrayList<>(annotations));
    }

    public String getInternalName() {
        return internalName;
    }

    public String getClassName() {
        return internalName.replace('/', '.');
    }

    public String getSuperName() {
        return superName;
    }

    public List<String> getInterfaces() {
        return interfaces;
    }

    public int getAccess() {
        return access;
    }

    public int getClassFileVersion() {
        return classFileVersion;
    }

    public List<BytecodeMethod> getMethods() {
        return methods;
    }

    public List<BytecodeField> getFields() {
        return fields;
    }

    public Set<String> getReferencedClasses() {
        return referencedClasses;
    }

    public List<String> getAnnotations() {
        return annotations;
    }

    public boolean isInterface() {
        return (access & org.objectweb.asm.Opcodes.ACC_INTERFACE) != 0;
    }

    public boolean isEnum() {
        return (access & org.objectweb.asm.Opcodes.ACC_ENUM) != 0;
    }

    public boolean isRecord() {
        return (access & 0x10000) != 0; // ACC_RECORD (Java 16+), avoid Opcodes dep drift
    }
}
