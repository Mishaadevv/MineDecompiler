package app.ui;

import app.core.DecompileOptions;
import app.core.DecompiledProject;
import app.core.GameVersion;
import app.core.VersionDetectionResult;
import app.core.VersionDetector;
import app.mappings.MappingFinder;
import app.mappings.download.MappingDownloader;
import app.cache.CachePaths;
import app.core.DecompileProgressListener;
import app.pipeline.DecompilationPipeline;
import app.project.ExportManager;
import app.core.Project;
import app.search.SearchIndex;

import javax.swing.*;
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
import java.util.TreeMap;
import java.util.prefs.Preferences;

/**
 * Main window (spec sections 9, 12, 26). Layout:
 * toolbar / input-output bar / split(classes | source) / status+log.
 */
public final class MainWindow extends JFrame {

    private final Preferences prefs = Preferences.userNodeForPackage(MainWindow.class);

    private final JTextField jarField = new JTextField();
    private final JTextField outField = new JTextField();
    private final JTextField versionField = new JTextField();
    private final JTextField mappingsField = new JTextField();
    private final JComboBox<String> decompilerBox = new JComboBox<>(new String[]{"auto", "vineflower", "cfr", "javap"});
    private final JCheckBox autoDownloadBox = new JCheckBox("Download mappings automatically");
    private final JButton decompileBtn = new JButton("Decompile");
    private final JButton cancelBtn = new JButton("Cancel");
    private final JButton openFolderBtn = new JButton("Open Output Folder");
    private final JButton openProjectBtn = new JButton("Open Project");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel statusLabel = new JLabel("Idle.");
    private final JLabel statsLabel = new JLabel("");
    private final JTextArea logArea = new JTextArea(8, 80);
    private final JTextArea sourceArea = new JTextArea();
    private final JTree classTree = new JTree(new DefaultMutableTreeNode("No project"));
    private final JTextField searchField = new JTextField();
    private final JList<String> methodList = new JList<>(new DefaultListModel<>());
    private final JLabel currentFileLabel = new JLabel(" ");

    private DecompileWorker worker;
    private DecompiledProject lastProject;
    private Map<String, Path> classToSource = new TreeMap<>();
    private Path sourcesRoot;

    public MainWindow() {
        super("Minecraft Source Reconstructor");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        buildUi();
        loadPrefs();
    }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        setLayout(new BorderLayout());

        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        JButton openJarBtn = new JButton("Open JAR");
        openJarBtn.addActionListener(e -> chooseJar());
        JButton exportBtn = new JButton("Export");
        exportBtn.addActionListener(e -> exportCurrentClass());
        JButton settingsBtn = new JButton("Settings");
        settingsBtn.addActionListener(e -> showSettings());
        toolbar.add(openJarBtn);
        toolbar.add(decompileBtn);
        toolbar.add(exportBtn);
        toolbar.add(settingsBtn);
        toolbar.addSeparator();
        toolbar.add(new JLabel("Decompiler:"));
        toolbar.add(decompilerBox);
        add(toolbar, BorderLayout.NORTH);

        // ---- Input / output panel (spec 26)
        JPanel io = new JPanel(new GridBagLayout());
        io.setBorder(BorderFactory.createTitledBorder("Input / Output"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        io.add(new JLabel("Input Minecraft JAR:"), c);
        c.gridx = 1; c.weightx = 1;
        jarField.setEditable(true);
        io.add(jarField, c);
        c.gridx = 2; c.weightx = 0;
        JButton browseJar = new JButton("Browse...");
        browseJar.addActionListener(e -> chooseJar());
        io.add(browseJar, c);

        c.gridx = 0; c.gridy = 1;
        io.add(new JLabel("Output directory:"), c);
        c.gridx = 1;
        io.add(outField, c);
        c.gridx = 2;
        JPanel outBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton chooseFolder = new JButton("Choose Folder...");
        chooseFolder.addActionListener(e -> chooseOutput());
        JButton suggest = new JButton("Suggest Folder");
        suggest.addActionListener(e -> suggestFolder());
        outBtns.add(chooseFolder);
        outBtns.add(suggest);
        io.add(outBtns, c);

        c.gridx = 0; c.gridy = 2;
        io.add(new JLabel("Version override:"), c);
        c.gridx = 1;
        versionField.setToolTipText("Leave empty for auto-detection");
        io.add(versionField, c);
        c.gridx = 2;
        JButton detectBtn = new JButton("Detect");
        detectBtn.addActionListener(e -> detectVersion());
        io.add(detectBtn, c);

        c.gridx = 0; c.gridy = 3;
        io.add(new JLabel("Mappings:"), c);
        c.gridx = 1;
        mappingsField.setToolTipText("Auto — press Find Mappings or drop a file/dir here");
        io.add(mappingsField, c);
        c.gridx = 2;
        JPanel mapBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton browseMappings = new JButton("Browse...");
        browseMappings.addActionListener(e -> chooseMappings());
        JButton findMappings = new JButton("Find Mappings");
        findMappings.setToolTipText("Automatically find local mappings for this version");
        findMappings.addActionListener(e -> findMappingsAction());
        mapBtns.add(browseMappings);
        mapBtns.add(findMappings);
        io.add(mapBtns, c);

        // Drag & drop for JAR (spec 26.5)
        jarField.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                try {
                    java.util.List<File> files =
                            (java.util.List<File>) support.getTransferable()
                                    .getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) {
                        jarField.setText(files.get(0).getAbsolutePath());
                        maybeSuggestOutput();
                        autoFillMappingsQuiet();
                        return true;
                    }
                } catch (Exception ignored) {
                }
                return false;
            }
        });

        // Drop hint label
        JLabel dropHint = new JLabel("Drop Minecraft JAR here or use Browse — then Choose Folder and press Decompile.",
                SwingConstants.CENTER);
        dropHint.setForeground(Color.GRAY);
        c.gridx = 1; c.gridy = 4; c.gridwidth = 2;
        autoDownloadBox.setToolTipText("Fetch Mojang/Feather mappings from official servers when missing locally (cached for offline use)");
        io.add(autoDownloadBox, c);
        c.gridx = 0; c.gridy = 5; c.gridwidth = 3;
        io.add(dropHint, c);
        c.gridwidth = 1;
        add(io, BorderLayout.NORTH);

        // ---- Main split
        sourceArea.setEditable(false);
        sourceArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane sourceScroll = new JScrollPane(sourceArea);

        JPanel leftPanel = new JPanel(new BorderLayout());
        JPanel searchPanel = new JPanel(new BorderLayout());
        searchPanel.add(new JLabel("Classes (Ctrl+P): "), BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);
        leftPanel.add(searchPanel, BorderLayout.NORTH);
        classTree.setRootVisible(true);
        leftPanel.add(new JScrollPane(classTree), BorderLayout.CENTER);
        methodList.setBorder(BorderFactory.createTitledBorder("Methods"));
        leftPanel.add(new JScrollPane(methodList), BorderLayout.SOUTH);
        leftPanel.setPreferredSize(new Dimension(320, 600));

        searchField.getDocument().addDocumentListener(new SimpleDocListener(this::filterTree));
        classTree.addTreeSelectionListener(e -> openSelectedClass());
        methodList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) jumpToMethod();
        });

        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.add(currentFileLabel, BorderLayout.NORTH);
        centerPanel.add(sourceScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, centerPanel);
        split.setDividerLocation(320);
        add(split, BorderLayout.CENTER);

        // ---- Bottom: progress + log
        JPanel bottom = new JPanel(new BorderLayout());
        JPanel statusBar = new JPanel(new BorderLayout());
        progress.setStringPainted(true);
        statusBar.add(statusLabel, BorderLayout.WEST);
        statusBar.add(progress, BorderLayout.CENTER);
        statusBar.add(statsLabel, BorderLayout.EAST);
        bottom.add(statusBar, BorderLayout.NORTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(800, 130));
        logScroll.setBorder(BorderFactory.createTitledBorder("Log"));
        bottom.add(logScroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        cancelBtn.setEnabled(false);
        openFolderBtn.setEnabled(false);
        openProjectBtn.setEnabled(false);
        actions.add(decompileBtn);
        actions.add(cancelBtn);
        actions.add(openFolderBtn);
        actions.add(openProjectBtn);
        bottom.add(actions, BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);

        decompileBtn.addActionListener(e -> startDecompile());
        cancelBtn.addActionListener(e -> {
            if (worker != null) worker.cancel(true);
        });
        openFolderBtn.addActionListener(e -> openOutputFolder());
        openProjectBtn.addActionListener(e -> refreshProjectView());

        // Keyboard shortcuts: Ctrl+P quick open, Ctrl+Shift+F search all, Ctrl+F find.
        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_P, KeyEvent.CTRL_DOWN_MASK), "quickOpen");
        am.put("quickOpen", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { quickOpen(); }
        });
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F,
                KeyEvent.CTRL_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK), "searchAll");
        am.put("searchAll", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { searchAll(); }
        });

        // Ctrl+Click -> go to definition (best-effort: find type under cursor).
        sourceArea.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.isControlDown()) goToDefinitionAtCaret();
            }
        });
    }

    // ------------------------------------------------------------- actions

    private void chooseJar() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Minecraft JAR", "jar", "zip"));
        String last = prefs.get("lastJarDir", System.getProperty("user.home"));
        fc.setCurrentDirectory(new File(last));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            jarField.setText(fc.getSelectedFile().getAbsolutePath());
            prefs.put("lastJarDir", fc.getSelectedFile().getParent());
            maybeSuggestOutput();
            autoFillMappingsQuiet();
        }
    }

    private void chooseOutput() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String def = prefs.get("defaultOutput", System.getProperty("user.home"));
        fc.setCurrentDirectory(new File(def));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            outField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void suggestFolder() {
        String base = prefs.get("defaultOutput", System.getProperty("user.home"));
        String v = versionField.getText().trim();
        if (v.isEmpty()) {
            // Try quick detection from JAR name.
            try {
                Path jar = Path.of(jarField.getText().trim());
                if (Files.isRegularFile(jar)) {
                    VersionDetectionResult r = VersionDetector.detect(jar, null);
                    v = r.getBest().getId();
                }
            } catch (Exception ignored) {
            }
        }
        if (v.isEmpty()) v = "unknown";
        outField.setText(Path.of(base, DecompileOptions.suggestFolderName(v)).toString());
    }

    private void chooseMappings() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            mappingsField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private Path libraryDirFromPrefs() {
        String lib = prefs.get("mappingsLibrary", "");
        return lib.isBlank() ? null : Path.of(lib.trim());
    }

    /** Resolves the working version: override field, else auto-detect from JAR. */
    private GameVersion resolveWorkingVersion() {
        String override = versionField.getText().trim();
        if (!override.isEmpty()) {
            return GameVersion.classify(override);
        }
        try {
            Path jar = Path.of(jarField.getText().trim());
            if (Files.isRegularFile(jar)) {
                return VersionDetector.detect(jar, null).getBest();
            }
        } catch (Exception ignored) {
        }
        return GameVersion.classify("unknown");
    }

    /** Shared local-search + optional auto-download worker for Find/auto-fill. */
    private void runMappingSearch(GameVersion version, Path jar, Path out, Path lib,
                                  boolean allowDownload,
                                  java.util.function.Consumer<List<MappingFinder.Candidate>> onDone) {
        new SwingWorker<List<MappingFinder.Candidate>, String>() {
            @Override
            protected List<MappingFinder.Candidate> doInBackground() {
                final SwingWorker<List<MappingFinder.Candidate>, String> self = this;
                publish("Finding mappings for " + version + " ...");
                List<MappingFinder.Candidate> found = MappingFinder.search(version, jar, out, lib);
                if (found.isEmpty() && allowDownload && !isCancelled()) {
                    publish("No local mappings - trying automatic download ...");
                    DecompileProgressListener fwd = new DecompileProgressListener() {
                        @Override public void onStatus(String m) { publish(m); }
                        @Override public void onWarning(String w) { publish("[!] " + w); }
                        @Override public void onError(String f, String r) { publish("[X] " + f + ": " + r); }
                        @Override public boolean isCancelled() { return self.isCancelled(); }
                    };
                    Path dl = new MappingDownloader().fetchBest(version, CachePaths.mappingsCache(), fwd);
                    if (dl != null && !isCancelled()) {
                        found = MappingFinder.search(version, jar, out, lib);
                    }
                }
                return found;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String m : chunks) {
                    log(m);
                }
            }

            @Override
            protected void done() {
                try {
                    onDone.accept(get());
                } catch (Exception ex) {
                    log("Find Mappings failed: " + ex.getMessage());
                    onDone.accept(List.of());
                }
            }
        }.execute();
    }

    /** Manual "Find Mappings": scans local sources, fills the field (asks when ambiguous). */
    private void findMappingsAction() {
        GameVersion version = resolveWorkingVersion();
        Path jar = jarField.getText().isBlank() ? null : Path.of(jarField.getText().trim());
        Path out = outField.getText().isBlank() ? null : Path.of(outField.getText().trim());
        Path lib = libraryDirFromPrefs();
        boolean allowDownload = autoDownloadBox.isSelected();
        runMappingSearch(version, jar, out, lib, allowDownload, found -> {
            if (found.isEmpty()) {
                log("No mappings available (local or download). Continuing with synthetic names is fine.");
                JOptionPane.showMessageDialog(MainWindow.this,
                        "No mappings found for " + version + " (local or download).\n\n"
                                + "Searched: <output>/mappings/, ./mappings/, JAR folder"
                                + (lib == null ? "" : ",\n" + lib)
                                + ", download cache"
                                + (allowDownload ? "." : " (downloads disabled).")
                                + "\n\nDecompilation still works with synthetic names.",
                        "Find Mappings", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (found.size() == 1) {
                applyMappingCandidate(found.get(0));
                return;
            }
            Object[] opts = found.stream().map(MappingFinder.Candidate::displayName).toArray();
            String pick = (String) JOptionPane.showInputDialog(MainWindow.this,
                    "Multiple mapping sets found for " + version + ":",
                    "Find Mappings", JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
            if (pick != null) {
                for (var c : found) {
                    if (c.displayName().equals(pick)) {
                        applyMappingCandidate(c);
                        break;
                    }
                }
            }
        });
    }

    private void applyMappingCandidate(app.mappings.MappingFinder.Candidate c) {
        mappingsField.setText(c.path().toString());
        log("Mappings selected: " + c.path() + " [" + c.format() + "]");
    }

    /** Quiet auto-fill after JAR selection: fills only on one strong hit, else just logs. */
    private void autoFillMappingsQuiet() {
        if (!mappingsField.getText().isBlank()) {
            return; // never override the user's explicit choice
        }
        String jarText = jarField.getText().trim();
        if (jarText.isEmpty() || !Files.isRegularFile(Path.of(jarText))) {
            return;
        }
        GameVersion version = resolveWorkingVersion();
        Path jar = Path.of(jarText);
        Path out = outField.getText().isBlank() ? null : Path.of(outField.getText().trim());
        Path lib = libraryDirFromPrefs();
        runMappingSearch(version, jar, out, lib, autoDownloadBox.isSelected(), found -> {
            if (!mappingsField.getText().isBlank()) {
                return;
            }
            if (found.isEmpty()) {
                return;
            }
            var best = found.get(0);
            if (best.score() >= MappingFinder.AUTO_FILL_THRESHOLD) {
                applyMappingCandidate(best);
                log("Auto-filled mappings for " + version + ".");
            } else {
                log("Found " + found.size() + " mapping set(s), press Find Mappings to choose.");
            }
        });
    }

    /** Suggests the output folder automatically when the user picked a JAR but no output. */
    private void maybeSuggestOutput() {
        if (!outField.getText().isBlank()) {
            return;
        }
        String jarText = jarField.getText().trim();
        if (jarText.isEmpty()) {
            return;
        }
        String v = versionField.getText().trim();
        if (v.isEmpty()) {
            try {
                Path jar = Path.of(jarText);
                if (Files.isRegularFile(jar)) {
                    v = VersionDetector.detect(jar, null).getBest().getId();
                }
            } catch (Exception ignored) {
            }
        }
        if (v.isEmpty() || v.startsWith("unknown")) {
            v = Path.of(jarText).getFileName().toString().replaceAll("(?i)\\.jar$", "");
        }
        outField.setText(Path.of(prefs.get("defaultOutput", System.getProperty("user.home")),
                DecompileOptions.suggestFolderName(v)).toString());
        log("Suggested output: " + outField.getText());
    }

    private void detectVersion() {
        try {
            Path jar = Path.of(jarField.getText().trim());
            VersionDetectionResult r = VersionDetector.detect(jar, null);
            log("Detected: " + r.getBest() + " (" + Math.round(r.getConfidence() * 100) + "%)");
            for (String e : r.getEvidence()) log("  evidence: " + e);
            if (!r.isConfident()) {
                JOptionPane.showMessageDialog(this,
                        "Could not determine Minecraft version confidently.\n\nCandidates:\n - "
                                + String.join("\n - ", r.getCandidates())
                                + "\n\nEnter the version manually if needed.",
                        "Version detection", JOptionPane.WARNING_MESSAGE);
            } else {
                versionField.setText(r.getBest().getId().startsWith("unknown") ? "" : r.getBest().getId());
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Detection failed: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showSettings() {
        JTextField defOut = new JTextField(prefs.get("defaultOutput", System.getProperty("user.home")), 40);
        JTextField mapLib = new JTextField(prefs.get("mappingsLibrary", ""), 40);
        mapLib.setToolTipText("Folder scanned by Find Mappings (may contain version subfolders)");
        JTextArea jvmInfo = new JTextArea(app.util.JavaDiscovery.describe(), 10, 40);
        jvmInfo.setEditable(false);
        jvmInfo.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        JPanel topRows = new JPanel(new GridLayout(2, 1, 4, 4));
        JPanel row1 = new JPanel(new BorderLayout(4, 4));
        row1.add(new JLabel("Default output directory:"), BorderLayout.WEST);
        row1.add(defOut, BorderLayout.CENTER);
        JPanel row2 = new JPanel(new BorderLayout(4, 4));
        row2.add(new JLabel("Mappings library folder:"), BorderLayout.WEST);
        row2.add(mapLib, BorderLayout.CENTER);
        topRows.add(row1);
        topRows.add(row2);
        panel.add(topRows, BorderLayout.NORTH);
        JPanel jvmPanel = new JPanel(new BorderLayout());
        jvmPanel.setBorder(BorderFactory.createTitledBorder(
                "Java runtimes (auto-selected, minimum Java " + app.util.JavaDiscovery.REQUIRED_MAJOR + ")"));
        jvmPanel.add(new JScrollPane(jvmInfo), BorderLayout.CENTER);
        panel.add(jvmPanel, BorderLayout.CENTER);
        int ok = JOptionPane.showConfirmDialog(this, panel, "Settings",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok == JOptionPane.OK_OPTION) {
            if (!defOut.getText().isBlank()) {
                prefs.put("defaultOutput", defOut.getText().trim());
                log("Default output directory: " + defOut.getText().trim());
            }
            prefs.put("mappingsLibrary", mapLib.getText().trim());
            if (!mapLib.getText().isBlank()) {
                log("Mappings library: " + mapLib.getText().trim());
                autoFillMappingsQuiet();
            }
        }
    }

    private void startDecompile() {
        String jarText = jarField.getText().trim();
        String outText = outField.getText().trim();
        if (jarText.isEmpty() || outText.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Select both the Minecraft JAR and the output directory first.",
                    "Missing input", JOptionPane.WARNING_MESSAGE);
            return;
        }
        Path jar = Path.of(jarText);
        Path out = Path.of(outText);
        if (!Files.isRegularFile(jar)) {
            JOptionPane.showMessageDialog(this, "Input JAR not found:\n" + jar,
                    "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        // 26.2: warn on non-empty output dir (never delete silently).
        if (Files.isDirectory(out)) {
            try (var s = Files.list(out)) {
                if (s.findAny().isPresent()) {
                    Object[] opts = {"Cancel", "Use This Folder", "Create New Subfolder"};
                    int choice = JOptionPane.showOptionDialog(this,
                            "The selected directory is not empty.\nExisting files may be overwritten.\n\n" + out,
                            "Directory not empty", JOptionPane.DEFAULT_OPTION,
                            JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
                    if (choice == 0 || choice == JOptionPane.CLOSED_OPTION) return;
                    if (choice == 2) {
                        out = out.resolve(DecompileOptions.suggestFolderName(versionField.getText()));
                        outField.setText(out.toString());
                    }
                }
            } catch (Exception ignored) {
            }
        }
        DecompileOptions options = DecompileOptions.builder(jar, out)
                .decompiler((String) decompilerBox.getSelectedItem())
                .customMappings(mappingsField.getText().isBlank() ? null : Path.of(mappingsField.getText().trim()))
                .mappingsLibrary(libraryDirFromPrefs())
                .autoDownloadMappings(autoDownloadBox.isSelected())
                .versionOverride(versionField.getText().isBlank() ? null : versionField.getText().trim())
                .build();
        savePrefs();
        logArea.setText("");
        setRunning(true);
        log("Minecraft JAR:\n  " + jar + "\nOutput:\n  " + out
                + "\nRuntime: Java " + app.util.JavaDiscovery.currentMajor());
        worker = new DecompileWorker(options, new DecompileWorker.Callback() {
            @Override public void onLog(String m) { log(m); }
            @Override public void onProgress(int pct, String current, int done, int total) {
                progress.setValue(pct);
                progress.setString(pct + "%  " + done + "/" + total);
                currentFileLabel.setText("Output: " + outField.getText().trim()
                        + "   |   " + (current == null ? "" : current));
                statusLabel.setText("Decompiling... " + pct + "%");
            }
            @Override public void onDone(DecompiledProject p) {
                setRunning(false);
                lastProject = p;
                classToSource = new TreeMap<>(p.getClassToSource());
                sourcesRoot = p.getSourcesRoot();
                openFolderBtn.setEnabled(true);
                openProjectBtn.setEnabled(true);
                statsLabel.setText("Classes: " + p.getStats().getClassesDecompiled()
                        + "  Failed: " + p.getFailedClasses().size()
                        + "  Warnings: " + p.getStats().getWarnings());
                statusLabel.setText("Completed in " + String.format("%.1f", p.getStats().getElapsedSeconds()) + "s");
                progress.setValue(100);
                log("Decompilation completed.\nOutput:\n  " + p.getOutputDirectory()
                        + "\nClasses: " + p.getStats().getClassesDecompiled()
                        + "  Failed: " + p.getFailedClasses().size()
                        + "  Warnings: " + p.getStats().getWarnings());
                refreshProjectView();
                JOptionPane.showMessageDialog(MainWindow.this,
                        "Decompilation completed.\n\nOutput:\n" + p.getOutputDirectory()
                                + "\n\nClasses: " + p.getStats().getClassesDecompiled()
                                + "  Failed: " + p.getFailedClasses().size()
                                + "\nWarnings: " + p.getStats().getWarnings(),
                        "Done", JOptionPane.INFORMATION_MESSAGE);
            }
            @Override public void onFailed(String error) {
                setRunning(false);
                statusLabel.setText("Failed.");
                log("FAILED: " + error);
                JOptionPane.showMessageDialog(MainWindow.this, error, "Decompilation failed",
                        JOptionPane.ERROR_MESSAGE);
            }
            @Override public void onCancelled() {
                setRunning(false);
                statusLabel.setText("Cancelled.");
                log("Cancelled by user.");
            }
        });
        worker.execute();
    }

    private void setRunning(boolean running) {
        decompileBtn.setEnabled(!running);
        cancelBtn.setEnabled(running);
    }

    private void openOutputFolder() {
        Path out = lastProject != null ? lastProject.getOutputDirectory()
                : Path.of(outField.getText().trim());
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(out.toFile());
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Cannot open folder:\n" + ex.getMessage());
        }
    }

    // ------------------------------------------------------- project view

    private void refreshProjectView() {
        // If no pipeline result yet, try opening the output dir directly.
        if (classToSource.isEmpty()) {
            try {
                String outText = outField.getText().trim();
                if (!outText.isEmpty() && Files.isDirectory(Path.of(outText))) {
                    Project prj = ExportManager.openProject(Path.of(outText));
                    Map<String, Path> map = new TreeMap<>();
                    for (String cls : prj.getClasses()) {
                        map.put(cls.replace('.', '/'),
                                prj.getSourcesRoot().resolve(cls.replace('.', '/') + ".java"));
                    }
                    classToSource = map;
                    sourcesRoot = prj.getSourcesRoot();
                }
            } catch (Exception ex) {
                log("Open project failed: " + ex.getMessage());
                return;
            }
        }
        if (classToSource.isEmpty()) {
            log("Nothing to show yet — decompile first.");
            return;
        }
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(
                sourcesRoot != null ? sourcesRoot.getFileName() + " (" + classToSource.size() + " classes)" : "Project");
        Map<String, DefaultMutableTreeNode> pkgNodes = new TreeMap<>();
        for (String internal : classToSource.keySet()) {
            String[] parts = internal.split("/");
            StringBuilder pkg = new StringBuilder();
            DefaultMutableTreeNode parent = root;
            for (int i = 0; i < parts.length - 1; i++) {
                if (pkg.length() > 0) pkg.append('/');
                pkg.append(parts[i]);
                final String seg = parts[i];
                parent = pkgNodes.computeIfAbsent(pkg.toString(), k -> {
                    DefaultMutableTreeNode n = new DefaultMutableTreeNode(seg);
                    // attach under correct parent
                    String pp = k.contains("/") ? k.substring(0, k.lastIndexOf('/')) : null;
                    (pp == null ? root : pkgNodes.get(pp)).add(n);
                    return n;
                });
            }
            parent.add(new DefaultMutableTreeNode(new ClassNode(internal)));
        }
        classTree.setModel(new DefaultTreeModel(root));
        expandFirstLevels();
        log("Project opened: " + classToSource.size() + " classes.");
    }

    private void expandFirstLevels() {
        for (int i = 0; i < Math.min(8, classTree.getRowCount()); i++) {
            classTree.expandRow(i);
        }
    }

    private void filterTree() {
        String q = searchField.getText().trim().toLowerCase();
        if (q.isEmpty() || classToSource.isEmpty()) {
            refreshProjectView();
            return;
        }
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Filter: " + q);
        int n = 0;
        for (Map.Entry<String, Path> e : classToSource.entrySet()) {
            String dotted = e.getKey().replace('/', '.');
            if (dotted.toLowerCase().contains(q)) {
                root.add(new DefaultMutableTreeNode(new ClassNode(e.getKey())));
                if (++n >= 500) break;
            }
        }
        classTree.setModel(new DefaultTreeModel(root));
        classTree.expandRow(0);
    }

    private void openSelectedClass() {
        TreePath tp = classTree.getSelectionPath();
        if (tp == null) return;
        Object last = ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
        if (last instanceof ClassNode cn) {
            openClass(cn.internal);
        }
    }

    private void openClass(String internal) {
        Path f = classToSource.get(internal);
        if (f == null || !Files.isRegularFile(f)) return;
        try {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            sourceArea.setText(src);
            sourceArea.setCaretPosition(0);
            currentFileLabel.setText(internal.replace('/', '.') + "  —  " + f);
            // Methods outline
            DefaultListModel<String> model = new DefaultListModel<>();
            for (String m : SearchIndex.listMethods(f)) model.addElement(m);
            methodList.setModel(model);
        } catch (Exception ex) {
            sourceArea.setText("// Cannot read " + f + ": " + ex.getMessage());
        }
    }

    private void jumpToMethod() {
        String sel = methodList.getSelectedValue();
        if (sel == null) return;
        String name = sel.split("\\(")[0].replaceAll(".*\\s", "");
        String text = sourceArea.getText();
        int idx = text.indexOf(name + "(");
        if (idx >= 0) {
            sourceArea.setCaretPosition(idx);
            sourceArea.requestFocus();
        }
    }

    private void quickOpen() {
        if (classToSource.isEmpty()) return;
        String q = JOptionPane.showInputDialog(this, "Quick open class (Ctrl+P):", searchField.getText());
        if (q == null || q.isBlank()) return;
        SearchIndex idx = new SearchIndex(classToSource);
        List<String> found = idx.findClasses(q.trim(), 20);
        if (found.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No class matches: " + q);
            return;
        }
        String pick = found.size() == 1 ? found.get(0)
                : (String) JOptionPane.showInputDialog(this, "Select class:", "Quick open",
                JOptionPane.PLAIN_MESSAGE, null, found.toArray(), found.get(0));
        if (pick != null) openClass(pick.replace('.', '/'));
    }

    private void searchAll() {
        if (classToSource.isEmpty()) return;
        String q = JOptionPane.showInputDialog(this, "Search all sources (Ctrl+Shift+F):");
        if (q == null || q.isBlank()) return;
        try {
            SearchIndex idx = new SearchIndex(classToSource);
            List<SearchIndex.Hit> hits = idx.searchAll(q, 200);
            if (hits.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No matches for: " + q);
                return;
            }
            String[] opts = hits.stream()
                    .map(h -> h.className() + ":" + h.line() + "  " + h.preview())
                    .toArray(String[]::new);
            String pick = (String) JOptionPane.showInputDialog(this,
                    hits.size() + " matches (first 200):", "Search results",
                    JOptionPane.PLAIN_MESSAGE, null, opts, opts[0]);
            if (pick != null) {
                String cls = pick.split(":")[0];
                openClass(cls.replace('.', '/'));
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Search failed: " + ex.getMessage());
        }
    }

    private void goToDefinitionAtCaret() {
        try {
            int pos = sourceArea.getCaretPosition();
            String text = sourceArea.getText();
            int s = pos, e = pos;
            while (s > 0 && Character.isJavaIdentifierPart(text.charAt(s - 1))) s--;
            while (e < text.length() && Character.isJavaIdentifierPart(text.charAt(e))) e++;
            if (e <= s) return;
            String word = text.substring(s, e);
            SearchIndex idx = new SearchIndex(classToSource);
            List<String> found = idx.findClasses(word, 10);
            if (!found.isEmpty()) openClass(found.get(0).replace('.', '/'));
            else searchField.setText(word);
        } catch (Exception ignored) {
        }
    }

    private void exportCurrentClass() {
        TreePath tp = classTree.getSelectionPath();
        if (tp == null || lastProject == null && classToSource.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Select a class first.");
            return;
        }
        Object last = ((DefaultMutableTreeNode) tp.getLastPathComponent()).getUserObject();
        if (!(last instanceof ClassNode cn)) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File(cn.simpleName() + ".java"));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.copy(classToSource.get(cn.internal), fc.getSelectedFile().toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log("Exported " + cn.internal + " -> " + fc.getSelectedFile());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage());
            }
        }
    }

    private void log(String m) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(m + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void loadPrefs() {
        jarField.setText(prefs.get("lastJar", ""));
        autoDownloadBox.setSelected(prefs.getBoolean("autoDownloadMappings", true));
        String defOut = prefs.get("defaultOutput", "");
        if (!defOut.isEmpty() && outField.getText().isEmpty()) outField.setText(defOut);
    }

    private void savePrefs() {
        prefs.put("lastJar", jarField.getText().trim());
        prefs.putBoolean("autoDownloadMappings", autoDownloadBox.isSelected());
    }

    private record ClassNode(String internal) {
        String simpleName() {
            String s = internal.substring(internal.lastIndexOf('/') + 1).split("\\$")[0];
            return s.isEmpty() ? internal : s;
        }

        @Override public String toString() { return simpleName() + ".java"; }
    }

    private static class SimpleDocListener implements javax.swing.event.DocumentListener {
        private final Runnable r;
        SimpleDocListener(Runnable r) { this.r = r; }
        @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
    }
}
