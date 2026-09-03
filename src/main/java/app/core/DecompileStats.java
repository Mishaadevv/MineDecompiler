package app.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe statistics accumulator for one run.
 */
public final class DecompileStats {

    private final AtomicInteger classesAnalyzed = new AtomicInteger();
    private final AtomicInteger classesDecompiled = new AtomicInteger();
    private final AtomicInteger mappingsApplied = new AtomicInteger();
    private final AtomicInteger errors = new AtomicInteger();
    private final AtomicInteger warnings = new AtomicInteger();
    private final List<String> errorDetails = Collections.synchronizedList(new ArrayList<>());
    private final List<String> warningDetails = Collections.synchronizedList(new ArrayList<>());
    private volatile long startedNanos = System.nanoTime();
    private volatile long finishedNanos;

    public void incAnalyzed() {
        classesAnalyzed.incrementAndGet();
    }

    public void incAnalyzed(int n) {
        classesAnalyzed.addAndGet(n);
    }

    public void incDecompiled() {
        classesDecompiled.incrementAndGet();
    }

    public void incMappings(int n) {
        mappingsApplied.addAndGet(n);
    }

    public void addError(String detail) {
        errors.incrementAndGet();
        if (detail != null) {
            errorDetails.add(detail);
        }
    }

    public void addWarning(String detail) {
        warnings.incrementAndGet();
        if (detail != null) {
            warningDetails.add(detail);
        }
    }

    public void finish() {
        finishedNanos = System.nanoTime();
    }

    public int getClassesAnalyzed() {
        return classesAnalyzed.get();
    }

    public int getClassesDecompiled() {
        return classesDecompiled.get();
    }

    public int getMappingsApplied() {
        return mappingsApplied.get();
    }

    public int getErrors() {
        return errors.get();
    }

    public int getWarnings() {
        return warnings.get();
    }

    public List<String> getErrorDetails() {
        return List.copyOf(errorDetails);
    }

    public List<String> getWarningDetails() {
        return List.copyOf(warningDetails);
    }

    public double getElapsedSeconds() {
        long end = finishedNanos == 0 ? System.nanoTime() : finishedNanos;
        return (end - startedNanos) / 1_000_000_000.0;
    }

    public String summary() {
        return String.format("Classes analyzed: %d%nClasses decompiled: %d%nMappings applied: %d%nErrors: %d%nWarnings: %d%nElapsed: %.1fs",
                getClassesAnalyzed(), getClassesDecompiled(), getMappingsApplied(),
                getErrors(), getWarnings(), getElapsedSeconds());
    }
}
