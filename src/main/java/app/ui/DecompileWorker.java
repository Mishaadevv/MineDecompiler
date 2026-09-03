package app.ui;

import app.core.DecompileOptions;
import app.core.DecompileProgressListener;
import app.core.DecompiledProject;
import app.pipeline.DecompilationPipeline;

import javax.swing.*;

/**
 * Runs the shared {@link DecompilationPipeline} off the EDT with
 * cancellation support. GUI-only glue: the pipeline itself knows
 * nothing about Swing.
 */
public final class DecompileWorker extends SwingWorker<DecompiledProject, String> {

    /** UI callbacks, always invoked on the EDT. */
    public interface Callback {
        void onLog(String message);

        void onProgress(int percent, String current, int done, int total);

        void onDone(DecompiledProject project);

        void onFailed(String error);

        void onCancelled();
    }

    private final DecompileOptions options;
    private final Callback callback;

    public DecompileWorker(DecompileOptions options, Callback callback) {
        this.options = options;
        this.callback = callback;
    }

    @Override
    protected DecompiledProject doInBackground() throws Exception {
        DecompilationPipeline pipeline = new DecompilationPipeline();
        DecompileProgressListener listener = new DecompileProgressListener() {
            @Override
            public void onStatus(String message) {
                publish(message);
            }

            @Override
            public void onProgress(double fraction, String currentFile, int done, int total) {
                SwingUtilities.invokeLater(() -> callback.onProgress(
                        (int) Math.round(fraction * 100), currentFile, done, total));
            }

            @Override
            public void onWarning(String warning) {
                publish("[!] " + warning);
            }

            @Override
            public void onError(String file, String reason) {
                publish("[X] " + file + ": " + reason);
            }

            @Override
            public boolean isCancelled() {
                return DecompileWorker.this.isCancelled();
            }
        };
        return pipeline.run(options, listener);
    }

    @Override
    protected void process(java.util.List<String> chunks) {
        for (String m : chunks) {
            callback.onLog(m);
        }
    }

    @Override
    protected void done() {
        if (isCancelled()) {
            callback.onCancelled();
            return;
        }
        try {
            callback.onDone(get());
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            callback.onFailed(cause.getMessage() != null ? cause.getMessage() : cause.toString());
        }
    }
}
