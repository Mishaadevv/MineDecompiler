package app.ui;

import app.util.JavaAutoLauncher;
import app.util.JavaDiscovery;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Entry point for the desktop GUI (Swing — chosen for: zero extra runtime
 * deps, &lt;100ms startup, ~30MB idle RAM, native LAF, fully async pipeline).
 * All heavy work runs in {@link DecompileWorker}; the EDT never blocks.
 */
public final class App {

    public static void main(String[] args) {
        if (JavaAutoLauncher.ensureJava(JavaDiscovery.REQUIRED_MAJOR, "app.ui.App", args)) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            new MainWindow().setVisible(true);
        });
    }
}
