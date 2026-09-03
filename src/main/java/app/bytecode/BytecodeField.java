package app.bytecode;

import java.util.Objects;

/** Immutable view of one field discovered by ASM. */
public final class BytecodeField {
    private final String name;
    private final String descriptor;
    private final int access;
    private final Object constantValue;

    public BytecodeField(String name, String descriptor, int access, Object constantValue) {
        this.name = Objects.requireNonNull(name);
        this.descriptor = Objects.requireNonNull(descriptor);
        this.access = access;
        this.constantValue = constantValue;
    }

    public String getName() {
        return name;
    }

    public String getDescriptor() {
        return descriptor;
    }

    public int getAccess() {
        return access;
    }

    public Object getConstantValue() {
        return constantValue;
    }
}
