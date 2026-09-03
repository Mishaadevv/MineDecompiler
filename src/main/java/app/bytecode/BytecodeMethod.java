package app.bytecode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable view of one method discovered by ASM. */
public final class BytecodeMethod {
    private final String name;
    private final String descriptor;
    private final int access;
    private final String signature;

    public BytecodeMethod(String name, String descriptor, int access, String signature) {
        this.name = Objects.requireNonNull(name);
        this.descriptor = Objects.requireNonNull(descriptor);
        this.access = access;
        this.signature = signature;
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

    public String getSignature() {
        return signature;
    }
}
