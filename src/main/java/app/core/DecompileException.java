package app.core;

/** Unchecked failure for fatal pipeline errors (per-class failures are recorded, not thrown). */
public class DecompileException extends RuntimeException {
    public DecompileException(String message) {
        super(message);
    }

    public DecompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
