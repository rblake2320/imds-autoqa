package autoqa.gui;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Swing desktop GUI for IMDS AutoQA.
 *
 * <p>Launched with {@code javaw -jar autoqa.jar} (no console window) via
 * {@code autoqa.bat}.  Each toolbar action spawns the CLI as a sub-process so
 * native hooks (JNativeHook) and {@code System.exit()} calls are fully isolated
 * from the GUI JVM.
 *
 * <p>The GUI is also reachable by running {@code java -jar autoqa.jar} with no
 * arguments — {@link autoqa.cli.WrapperCLI#main} delegates here in that case.
 */
public class WrapperGUI extends JFrame {

    // ── UI components ─────────────────────────────────────────────────────────

    private final JTextArea  logArea;
    private final JLabel     statusLabel;
    private final JLabel     recordingFileLabel;

    private final JTextField urlFilterField;
    private final JComboBox<String> browserCombo;

    private final JButton btnStartRecord;
    private final JButton btnStopRecord;
    private final JButton btnBrowse;
    private final JButton btnPlay;
    private final JButton btnGenerate;
    private final JButton btnRun;
    private final JButton btnHeal;
    private final JButton btnReport;

    // ── State ─────────────────────────────────────────────────────────────────

    private Path    selectedRecording;
    private Process activeProcess;   // currently running sub-process

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "autoqa-worker");
        t.setDaemon(true);
        return t;
    });

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── Construction ──────────────────────────────────────────────────────────

    public WrapperGUI() {
        super("IMDS AutoQA");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(960, 640);
        setMinimumSize(new Dimension(780, 500));

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        // ── Log area (dark terminal-style) ───────────────────────────────────
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setBackground(new Color(24, 24, 28));
        logArea.setForeground(new Color(220, 220, 220));
        logArea.setCaretColor(Color.WHITE);
        logArea.setMargin(new Insets(8, 10, 8, 10));
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);

        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createEmptyBorder());

        // ── Build sidebar and status bar ─────────────────────────────────────
        urlFilterField     = new JTextField();
        browserCombo       = new JComboBox<>(new String[]{"Edge", "Chrome", "Firefox"});

        btnStartRecord     = makeButton("▶  Start Recording",  new Color(0, 130, 0));
        btnStopRecord      = makeButton("■  Stop Recording",   new Color(190, 0, 0));
        btnBrowse          = makeButton("📁  Browse…",          Color.DARK_GRAY);
        btnPlay            = makeButton("▶  Play Recording",   new Color(0, 100, 190));
        btnGenerate        = makeButton("⚡  Generate Test",    new Color(150, 85, 0));
        btnRun             = makeButton("▶▶  Run Suite",        new Color(0, 100, 190));
        btnHeal            = makeButton("🔧  Heal Locators",    new Color(110, 0, 150));
        btnReport          = makeButton("📊  View Report",      new Color(40, 40, 40));
        recordingFileLabel = new JLabel("No file selected");

        btnStopRecord.setEnabled(false);
        btnPlay.setEnabled(false);
        btnGenerate.setEnabled(false);
        btnHeal.setEnabled(false);

        statusLabel = new JLabel("●  Ready");
        statusLabel.setForeground(new Color(0, 155, 0));

        wireListeners();

        JSplitPane split = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, buildSidebar(), logScroll);
        split.setDividerLocation(220);
        split.setDividerSize(5);
        split.setBorder(null);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(buildHeader(),    BorderLayout.NORTH);
        getContentPane().add(split,            BorderLayout.CENTER);
        getContentPane().add(buildStatusBar(), BorderLayout.SOUTH);

        setLocationRelativeTo(null);
        log("IMDS AutoQA ready.");
        log("Select a recording file, then use the buttons on the left.");
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private JPanel buildHeader() {
        JPanel hdr = new JPanel(new BorderLayout());
        hdr.setBackground(new Color(0, 78, 152));
        hdr.setBorder(new EmptyBorder(10, 16, 10, 16));

        JLabel title = new JLabel("IMDS AutoQA");
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 20));
        title.setForeground(Color.WHITE);

        JLabel sub = new JLabel(
                "Air-gapped Selenium Recorder / Player  ·  Local LLM Self-Healing");
        sub.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        sub.setForeground(new Color(190, 215, 255));

        JPanel left = new JPanel(new GridLayout(2, 1, 0, 2));
        left.setOpaque(false);
        left.add(title);
        left.add(sub);

        JLabel ver = new JLabel("v1.0.0");
        ver.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        ver.setForeground(new Color(180, 205, 240));
        ver.setHorizontalAlignment(SwingConstants.RIGHT);

        hdr.add(left, BorderLayout.WEST);
        hdr.add(ver,  BorderLayout.EAST);
        return hdr;
    }

    // ── Sidebar ───────────────────────────────────────────────────────────────

    private JPanel buildSidebar() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(new Color(242, 242, 245));
        p.setBorder(new EmptyBorder(10, 10, 10, 8));

        // ── Recording ──────────────────────────────────────────────────────
        p.add(sectionLabel("RECORDING"));
        p.add(vGap(4));

        // URL focus filter
        JLabel urlLabel = new JLabel("Focus URL (optional)");
        urlLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        urlLabel.setForeground(new Color(110, 110, 120));
        urlLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        urlLabel.setBorder(new EmptyBorder(0, 2, 1, 0));
        p.add(urlLabel);

        urlFilterField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        urlFilterField.setAlignmentX(Component.LEFT_ALIGNMENT);
        urlFilterField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        urlFilterField.setToolTipText("Only record events on pages whose URL contains this text");
        urlFilterField.setForeground(new Color(40, 40, 40));
        p.add(urlFilterField);
        p.add(vGap(5));

        p.add(btnStartRecord);
        p.add(vGap(5));
        p.add(btnStopRecord);
        p.add(vGap(14));

        // ── Playback ───────────────────────────────────────────────────────
        p.add(sectionLabel("PLAYBACK"));
        p.add(vGap(4));

        recordingFileLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        recordingFileLabel.setForeground(new Color(130, 130, 130));
        recordingFileLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        recordingFileLabel.setBorder(new EmptyBorder(0, 4, 3, 0));
        p.add(recordingFileLabel);

        p.add(btnBrowse);
        p.add(vGap(4));

        // Browser selector
        JLabel browserLabel = new JLabel("Browser");
        browserLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        browserLabel.setForeground(new Color(110, 110, 120));
        browserLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        browserLabel.setBorder(new EmptyBorder(2, 2, 1, 0));
        p.add(browserLabel);

        browserCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        browserCombo.setAlignmentX(Component.LEFT_ALIGNMENT);
        browserCombo.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        p.add(browserCombo);
        p.add(vGap(5));

        p.add(btnPlay);
        p.add(vGap(14));

        // ── AI / Tests ─────────────────────────────────────────────────────
        p.add(sectionLabel("AI  /  TESTS"));
        p.add(vGap(4));
        p.add(btnGenerate);
        p.add(vGap(5));
        p.add(btnRun);
        p.add(vGap(5));
        p.add(btnHeal);
        p.add(vGap(14));

        // ── Report ─────────────────────────────────────────────────────────
        p.add(sectionLabel("REPORT"));
        p.add(vGap(4));
        p.add(btnReport);

        p.add(Box.createVerticalGlue());

        // Edge launch hint at bottom
        JLabel hint = new JLabel(
                "<html><font color='#888888' size='2'>Tip: launch Edge with<br>" +
                "--remote-debugging-port=9222<br>before recording.</font></html>");
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(new EmptyBorder(8, 4, 0, 0));
        p.add(hint);

        return p;
    }

    // ── Status bar ────────────────────────────────────────────────────────────

    private JPanel buildStatusBar() {
        JButton btnClear = new JButton("Clear Log");
        btnClear.setFocusPainted(false);
        btnClear.addActionListener(e -> logArea.setText(""));

        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(new CompoundBorder(
                new MatteBorder(1, 0, 0, 0, new Color(200, 200, 200)),
                new EmptyBorder(4, 12, 4, 10)));
        bar.add(statusLabel, BorderLayout.WEST);
        bar.add(btnClear,    BorderLayout.EAST);
        return bar;
    }

    // ── Action wiring ─────────────────────────────────────────────────────────

    private void wireListeners() {
        btnStartRecord.addActionListener(e -> startRecording());
        btnStopRecord .addActionListener(e -> stopRecording());
        btnBrowse     .addActionListener(e -> browseRecording());
        btnPlay       .addActionListener(e -> playRecording());
        btnGenerate   .addActionListener(e -> generateTest());
        btnRun        .addActionListener(e -> runSuite());
        btnHeal       .addActionListener(e -> healLocators());
        btnReport     .addActionListener(e -> viewReport());
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private void startRecording() {
        setStatus("●  Recording…", new Color(190, 0, 0));
        btnStartRecord.setEnabled(false);
        btnStopRecord.setEnabled(true);
        log("");

        String filter = urlFilterField.getText().trim();
        if (!filter.isEmpty()) {
            log("▶  Recording started — focused on URLs containing: " + filter);
            runCliAsync(true, "record", "start", "--url-filter", filter);
        } else {
            log("▶  Recording started — interact with Edge, then click Stop.");
            runCliAsync(true, "record", "start");
        }
    }

    private void stopRecording() {
        if (activeProcess != null && activeProcess.isAlive()) {
            activeProcess.destroy();
        }
        btnStartRecord.setEnabled(true);
        btnStopRecord.setEnabled(false);
        log("■  Recording stopped. Check recordings/ for your session file.");
        setStatus("●  Ready", new Color(0, 155, 0));
    }

    private void browseRecording() {
        Path startDir = Files.isDirectory(Path.of("recordings"))
                ? Path.of("recordings").toAbsolutePath()
                : Path.of(".").toAbsolutePath();

        JFileChooser fc = new JFileChooser(startDir.toFile());
        fc.setDialogTitle("Select a recording file");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Recording JSON (*.json)", "json"));

        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedRecording = fc.getSelectedFile().toPath();
            String name = selectedRecording.getFileName().toString();
            // truncate long names for display
            recordingFileLabel.setText(
                    name.length() > 26 ? "…" + name.substring(name.length() - 23) : name);
            recordingFileLabel.setForeground(new Color(0, 110, 0));
            btnPlay.setEnabled(true);
            btnGenerate.setEnabled(true);
            btnHeal.setEnabled(true);
            log("📁  Selected: " + selectedRecording.toAbsolutePath());
        }
    }

    private void playRecording() {
        if (selectedRecording == null) { browseRecording(); return; }
        String browser = ((String) browserCombo.getSelectedItem()).toLowerCase();
        log("");
        log("▶  Playing in " + browser + ": " + selectedRecording.getFileName());
        setStatus("●  Playing…", new Color(0, 100, 190));
        runCliAsync(false, "play",
                "--browser", browser,
                selectedRecording.toAbsolutePath().toString());
    }

    private void generateTest() {
        if (selectedRecording == null) { browseRecording(); return; }
        log("");
        log("⚡  Generating test — Ollama may take 30–60 seconds…");
        setStatus("●  Generating…", new Color(150, 85, 0));
        runCliAsync(false, "generate", selectedRecording.toAbsolutePath().toString());
    }

    private void runSuite() {
        log("");
        log("▶▶  Running TestNG regression suite…");
        setStatus("●  Running tests…", new Color(0, 100, 190));
        runCliAsync(false, "run");
    }

    private void healLocators() {
        if (selectedRecording == null) { browseRecording(); return; }
        log("");
        log("🔧  Healing locators for: " + selectedRecording.getFileName());
        setStatus("●  Healing…", new Color(110, 0, 150));
        runCliAsync(false, "heal", selectedRecording.toAbsolutePath().toString());
    }

    private void viewReport() {
        log("");
        log("📊  Launching Allure report viewer…");
        setStatus("●  Opening report…", Color.DARK_GRAY);
        runCliAsync(false, "report", "--serve");
    }

    // ── Sub-process runner ────────────────────────────────────────────────────

    /**
     * Spawns {@code java -jar <autoqa-jar> <args>} in a background thread and
     * pipes stdout+stderr to the log area line by line.
     *
     * @param keepAlive true when the process should stay running until the user
     *                  explicitly stops it (e.g. {@code record start})
     */
    private void runCliAsync(boolean keepAlive, String... cliArgs) {
        executor.submit(() -> {
            String jarPath = resolveJarPath();
            if (jarPath == null) {
                appendLog("ERROR: Cannot locate autoqa JAR. Rebuild with: mvn package");
                resetReady();
                return;
            }

            List<String> cmd = new ArrayList<>();
            cmd.add(resolveJavaExe());
            cmd.add("-jar");
            cmd.add(jarPath);
            cmd.addAll(Arrays.asList(cliArgs));

            appendLog("$  " + String.join(" ", cliArgs));

            try {
                ProcessBuilder pb = new ProcessBuilder(cmd)
                        .redirectErrorStream(true)
                        .directory(Path.of(".").toRealPath().toFile());

                activeProcess = pb.start();

                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(activeProcess.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        final String ln = line;
                        SwingUtilities.invokeLater(() -> appendToLog("   " + ln));
                    }
                }

                int exitCode = activeProcess.waitFor();

                if (!keepAlive) {
                    SwingUtilities.invokeLater(() -> {
                        if (exitCode == 0) {
                            appendToLog("✔  Completed successfully.");
                        } else {
                            appendToLog("✘  Exited with code " + exitCode + ".");
                        }
                        resetReady();
                    });
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    appendToLog("ERROR: " + ex.getMessage());
                    setStatus("●  Error", Color.RED);
                    resetReady();
                });
            }
        });
    }

    // ── JAR / Java resolution ─────────────────────────────────────────────────

    /** Finds the fat JAR that this class was loaded from. Falls back to target/. */
    private String resolveJarPath() {
        try {
            URI uri = WrapperGUI.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI();
            Path p = Path.of(uri);
            if (p.toString().endsWith(".jar") &&
                    !p.getFileName().toString().startsWith("original-")) {
                return p.toAbsolutePath().toString();
            }
        } catch (Exception ignored) {}

        // Running from IDE / dev: scan project root and target/ for the shaded JAR
        try {
            // Project-root portable JAR (produced by mvn package + copied by hand)
            Path rootJar = Path.of("autoqa.jar").toAbsolutePath();
            if (Files.exists(rootJar)) return rootJar.toString();

            // target/ shaded JAR (either new name or legacy name)
            return Files.walk(Path.of("target").toAbsolutePath(), 1)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return (n.equals("autoqa.jar") ||
                                (n.startsWith("imds-autoqa-") && n.endsWith(".jar")))
                                && !n.startsWith("original-");
                    })
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Returns the java(.exe) that launched this JVM, for consistent version. */
    private String resolveJavaExe() {
        String home = System.getProperty("java.home");
        if (home != null) {
            Path java = Path.of(home, "bin", "java.exe");
            if (Files.exists(java)) return java.toString();
            java = Path.of(home, "bin", "java");
            if (Files.exists(java)) return java.toString();
        }
        return "java";
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    /** Appends a timestamped line to the log (thread-safe). */
    private void log(String message) {
        SwingUtilities.invokeLater(() -> appendToLog(message));
    }

    /** Appends a timestamped line — must be called on the EDT. */
    private void appendToLog(String message) {
        String ts = LocalTime.now().format(TIME_FMT);
        logArea.append("[" + ts + "]  " + message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /** Appends a line without a timestamp (for sub-process output). */
    private void appendLog(String raw) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(raw + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void setStatus(String text, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(text);
            statusLabel.setForeground(color);
        });
    }

    private void resetReady() {
        btnStartRecord.setEnabled(true);
        btnStopRecord.setEnabled(false);
        setStatus("●  Ready", new Color(0, 155, 0));
    }

    private JButton makeButton(String text, Color fg) {
        JButton b = new JButton(text);
        b.setAlignmentX(Component.LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        b.setForeground(fg);
        b.setBackground(Color.WHITE);
        b.setFocusPainted(false);
        b.setBorder(new CompoundBorder(
                new LineBorder(new Color(205, 205, 210), 1, true),
                new EmptyBorder(6, 12, 6, 12)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        // Hover highlight
        b.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                if (b.isEnabled()) b.setBackground(new Color(240, 245, 255));
            }
            @Override public void mouseExited(MouseEvent e) {
                b.setBackground(Color.WHITE);
            }
        });
        return b;
    }

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
        l.setForeground(new Color(110, 110, 120));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(new EmptyBorder(2, 2, 2, 0));
        return l;
    }

    private Component vGap(int h) {
        return Box.createRigidArea(new Dimension(0, h));
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void launch() {
        SwingUtilities.invokeLater(() -> new WrapperGUI().setVisible(true));
    }

    public static void main(String[] args) {
        launch();
    }
}
