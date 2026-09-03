package app.core;

/**
 * Progress callback used by GUI, CLI and pipeline core alike.
 * All methods have defaults so lambdas/tests only override what they need.
 */
public interface DecompileProgressListener {

    default void onStatus(String message) {
    }

    default void onProgress(double fraction, String currentFile, int done, int total) {
    }

    default void onWarning(String warning) {
    }

    default void onError(String file, String reason) {
    }

    default boolean isCancelled() {
        return false;
    }

    /** Console-printing listener for CLI usage. */
    static DecompileProgressListener console() {
        return new DecompileProgressListener() {
            private int lastPct = -1;

            @Override
            public void onStatus(String message) {
                System.out.println("[*] " + message);
            }

            @Override
            public void onProgress(double fraction, String currentFile, int done, int total) {
                int pct = (int) Math.round(fraction * 100);
                if (pct != lastPct && (pct % 5 == 0 || done == total)) {
                    lastPct = pct;
                    System.out.printf("[%3d%%] %d/%d %s%n", pct, done, total,
                            currentFile == null ? "" : currentFile);
                }
            }

            @Override
            public void onWarning(String warning) {
                System.out.println("[!] " + warning);
            }

            @Override
            public void onError(String file, String reason) {
                System.out.println("[X] " + file + ": " + reason);
            }
        };
    }

    static DecompileProgressListener silent() {
        return new DecompileProgressListener() {
        };
    }
}
