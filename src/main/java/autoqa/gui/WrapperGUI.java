package autoqa.gui;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
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
 * {@code autoqa.bat}. Each toolbar action spawns the CLI as a sub-process so
 * native hooks (JNativeHook) and {@code System.exit()} calls are fully isolated
 * from the GUI JVM.
 *
 * <p>The GUI is also reachable by running {@code java -jar autoqa.jar} with no
 * arguments — {@link autoqa.cli.WrapperCLI#main} delegates here in that case.
 *
 * <p>Features:
 * <ul>
 *   <li>System Tray: minimize-to-tray on close, popup menu, double-click to restore.</li>
 *   <li>JTree Recording Browser: left panel lists all .json recordings, auto-refreshes.</li>
 *   <li>Keyboard Shortcuts: Ctrl+R/P/G/S/H, F5, Escape. Menu bar: File/Actions/View.</li>
 * </ul>
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

    // ── Recording browser ─────────────────────────────────────────────────────

    private DefaultTreeModel       treeModel;
    private DefaultMutableTreeNode treeRoot;
    private JTree                  recordingTree;
    private Timer                  treeRefreshTimer;

    // ── System tray ───────────────────────────────────────────────────────────

    private TrayIcon trayIcon;
    private boolean  traySupported;

    // ── State ─────────────────────────────────────────────────────────────────

    private Path    selectedRecording;
    private Process activeProcess;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "autoqa-worker");
        t.setDaemon(true);
        return t;
    });

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Path RECORDINGS_DIR = Path.of("recordings");

    // ── Construction ──────────────────────────────────────────────────────────

    public WrapperGUI() {
        super("IMDS AutoQA");
        setSize(1100, 680);
        setMinimumSize(new Dimension(860, 520));

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

        // ── Layout: recording browser | sidebar | log ─────────────────────────
        JPanel rightContent = new JPanel(new BorderLayout());
        JSplitPane sideLogSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, buildSidebar(), logScroll);
        sideLogSplit.setDividerLocation(220);
        sideLogSplit.setDividerSize(5);
        sideLogSplit.setBorder(null);
        rightContent.add(sideLogSplit, BorderLayout.CENTER);

        JSplitPane mainSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, buildRecordingBrowser(), rightContent);
        mainSplit.setDividerLocation(220);
        mainSplit.setDividerSize(5);
        mainSplit.setBorder(null);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(buildHeader(),    BorderLayout.NORTH);
        getContentPane().add(mainSplit,        BorderLayout.CENTER);
        getContentPane().add(buildStatusBar(), BorderLayout.SOUTH);

        setJMenuBar(buildMenuBar());

        // ── System tray setup ─────────────────────────────────────────────────
        initSystemTray();

        // ── Default close behaviour (minimize-to-tray if supported) ──────────
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleWindowClose();
            }
        });

        // ── Keyboard shortcuts ────────────────────────────────────────────────
        registerKeyboardShortcuts();

        // ── Start recording-tree auto-refresh ─────────────────────────────────
        treeRefreshTimer = new Timer(30_000, e -> refreshRecordingTree());
        treeRefreshTimer.setRepeats(true);
        treeRefreshTimer.start();

        setLocationRelativeTo(null);
        log("IMDS AutoQA ready.");
        log("Select a recording file, then use the buttons on the left.");
    }

    // ── System Tray ───────────────────────────────────────────────────────────

    private void initSystemTray() {
        traySupported = SystemTray.isSupported();
        if (!traySupported) return;

        Image icon = loadTrayIcon();

        PopupMenu popup = new PopupMenu();

        MenuItem miOpen = new MenuItem("Open AutoQA");
        miOpen.addActionListener(e -> showWindow());

        MenuItem miServer = new MenuItem("Start Server");
        miServer.addActionListener(e -> {
            showWindow();
            runCliAsync(false, "server");
        });

        MenuItem miBrowserIde = new MenuItem("Open Browser IDE");
        miBrowserIde.addActionListener(e -> openBrowserIde());

        MenuItem miQuit = new MenuItem("Quit");
        miQuit.addActionListener(e -> System.exit(0));

        popup.add(miOpen);
        popup.addSeparator();
        popup.add(miServer);
        popup.add(miBrowserIde);
        popup.addSeparator();
        popup.add(miQuit);

        trayIcon = new TrayIcon(icon, "IMDS AutoQA", popup);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> showWindow()); // double-click

        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException ex) {
            traySupported = false;
        }
    }

    /** Loads tray icon from resources, or synthesises a 16x16 navy+triangle image. */
    private Image loadTrayIcon() {
        try {
            var stream = getClass().getResourceAsStream("/autoqa-icon.png");
            if (stream != null) {
                return javax.imageio.ImageIO.read(stream);
            }
        } catch (Exception ignored) {}
        // Fallback: dark navy square with white right-pointing triangle
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0, 78, 152));
        g.fillRect(0, 0, 16, 16);
        g.setColor(Color.WHITE);
        int[] xs = {4, 4, 12};
        int[] ys = {3, 13, 8};
        g.fillPolygon(xs, ys, 3);
        g.dispose();
        return img;
    }

    private void handleWindowClose() {
        if (traySupported) {
            setVisible(false);
        } else {
            int choice = JOptionPane.showConfirmDialog(
                    this, "Quit IMDS AutoQA?", "Quit", JOptionPane.YES_NO_OPTION);
            if (choice == JOptionPane.YES_OPTION) System.exit(0);
        }
    }

    private void showWindow() {
        SwingUtilities.invokeLater(() -> {
            setVisible(true);
            setState(Frame.NORMAL);
            toFront();
            requestFocus();
        });
    }

    private void minimizeToTrayOrIconify() {
        if (traySupported) {
            setVisible(false);
        } else {
            setState(Frame.ICONIFIED);
        }
    }

    // ── Menu bar ──────────────────────────────────────────────────────────────

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        // File menu
        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        JMenuItem miQuit = new JMenuItem("Quit");
        miQuit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK));
        miQuit.addActionListener(e -> System.exit(0));
        fileMenu.add(miQuit);

        // Actions menu
        JMenu actionsMenu = new JMenu("Actions");
        actionsMenu.setMnemonic(KeyEvent.VK_A);

        JMenuItem miRecord = new JMenuItem("Record");
        miRecord.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
        miRecord.addActionListener(e -> startRecording());

        JMenuItem miPlay = new JMenuItem("Play");
        miPlay.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK));
        miPlay.addActionListener(e -> playRecording());

        JMenuItem miStop = new JMenuItem("Stop");
        miStop.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK));
        miStop.addActionListener(e -> stopRecording());

        JMenuItem miGenerate = new JMenuItem("Generate Test");
        miGenerate.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK));
        miGenerate.addActionListener(e -> generateTest());

        JMenuItem miHeal = new JMenuItem("Heal Locators");
        miHeal.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK));
        miHeal.addActionListener(e -> healLocators());

        actionsMenu.add(miRecord);
        actionsMenu.add(miPlay);
        actionsMenu.add(miStop);
        actionsMenu.addSeparator();
        actionsMenu.add(miGenerate);
        actionsMenu.add(miHeal);

        // View menu
        JMenu viewMenu = new JMenu("View");
        viewMenu.setMnemonic(KeyEvent.VK_V);

        JMenuItem miRefreshTree = new JMenuItem("Refresh Recordings");
        miRefreshTree.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        miRefreshTree.addActionListener(e -> refreshRecordingTree());

        JMenuItem miBrowserIde = new JMenuItem("Open Browser IDE");
        miBrowserIde.addActionListener(e -> openBrowserIde());

        viewMenu.add(miRefreshTree);
        viewMenu.add(miBrowserIde);

        bar.add(fileMenu);
        bar.add(actionsMenu);
        bar.add(viewMenu);
        return bar;
    }

    // ── Keyboard shortcuts ────────────────────────────────────────────────────

    private void registerKeyboardShortcuts() {
        InputMap  im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();

        bindKey(im, am, "shortcutRecord",
                KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK),
                e -> startRecording());
        bindKey(im, am, "shortcutPlay",
                KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK),
                e -> playRecording());
        bindKey(im, am, "shortcutGenerate",
                KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK),
                e -> generateTest());
        bindKey(im, am, "shortcutStop",
                KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK),
                e -> stopRecording());
        bindKey(im, am, "shortcutHeal",
                KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK),
                e -> healLocators());
        bindKey(im, am, "shortcutRefresh",
                KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0),
                e -> refreshRecordingTree());
        bindKey(im, am, "shortcutEscape",
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                e -> minimizeToTrayOrIconify());
    }

    /** Helper to bind a single KeyStroke to a lambda action. */
    private void bindKey(InputMap im, ActionMap am, String name,
                         KeyStroke ks, java.util.function.Consumer<ActionEvent> handler) {
        im.put(ks, name);
        am.put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { handler.accept(e); }
        });
    }

    // ── Recording browser (left panel) ────────────────────────────────────────

    private JPanel buildRecordingBrowser() {
        treeRoot  = new DefaultMutableTreeNode("Recordings");
        treeModel = new DefaultTreeModel(treeRoot);
        recordingTree = new JTree(treeModel);
        recordingTree.setRootVisible(true);
        recordingTree.setShowsRootHandles(true);
        recordingTree.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        recordingTree.getSelectionModel().setSelectionMode(
                TreeSelectionModel.SINGLE_TREE_SELECTION);

        // Double-click selects the recording
        recordingTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) selectFromTree();
            }
        });

        // Populate on startup
        refreshRecordingTree();

        // Toolbar above tree
        JButton btnRefresh = new JButton("\u27F3");
        btnRefresh.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        btnRefresh.setFocusPainted(false);
        btnRefresh.setToolTipText("Refresh recordings (F5)");
        btnRefresh.addActionListener(e -> refreshRecordingTree());

        JPanel toolbar = new JPanel(new BorderLayout());
        JLabel lbl = new JLabel("Recordings");
        lbl.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        lbl.setForeground(new Color(80, 80, 90));
        toolbar.add(lbl,        BorderLayout.WEST);
        toolbar.add(btnRefresh, BorderLayout.EAST);
        toolbar.setBackground(new Color(235, 235, 240));
        toolbar.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, new Color(200, 200, 205)),
                new EmptyBorder(4, 6, 4, 6)));

        JScrollPane treeScroll = new JScrollPane(recordingTree);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(new Color(245, 245, 248));
        panel.add(toolbar,    BorderLayout.NORTH);
        panel.add(treeScroll, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(220, 0));
        return panel;
    }

    /** Re-scans the recordings directory and rebuilds the tree model. */
    private void refreshRecordingTree() {
        treeRoot.removeAllChildren();

        if (!Files.isDirectory(RECORDINGS_DIR)) {
            treeRoot.add(new DefaultMutableTreeNode("No recordings yet"));
            treeModel.reload();
            expandAll();
            return;
        }

        try (var stream = Files.list(RECORDINGS_DIR)) {
            long count = stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .peek(p -> treeRoot.add(
                            new DefaultMutableTreeNode(p.getFileName().toString())))
                    .count();

            if (count == 0) {
                treeRoot.add(new DefaultMutableTreeNode("No recordings yet"));
            }
        } catch (IOException ex) {
            treeRoot.add(new DefaultMutableTreeNode("Error reading directory"));
        }

        SwingUtilities.invokeLater(() -> {
            treeModel.reload();
            expandAll();
        });
    }

    /** Expands all nodes in the tree. */
    private void expandAll() {
        for (int i = 0; i < recordingTree.getRowCount(); i++) {
            recordingTree.expandRow(i);
        }
    }

    /** Selects the recording corresponding to the currently highlighted tree node. */
    private void selectFromTree() {
        TreePath path = recordingTree.getSelectionPath();
        if (path == null) return;

        DefaultMutableTreeNode node =
                (DefaultMutableTreeNode) path.getLastPathComponent();
        String nodeName = node.getUserObject().toString();

        // Guard against placeholder and root nodes
        if (nodeName.startsWith("No recordings") || nodeName.startsWith("Error")
                || nodeName.equals("Recordings")) {
            return;
        }

        Path recording = RECORDINGS_DIR.resolve(nodeName);
        if (!Files.exists(recording)) {
            log("Recording not found: " + recording);
            return;
        }

        selectedRecording = recording;
        String display = nodeName.length() > 26
                ? "…" + nodeName.substring(nodeName.length() - 23) : nodeName;
        recordingFileLabel.setText(display);
        recordingFileLabel.setForeground(new Color(0, 110, 0));
        btnPlay.setEnabled(true);
        btnGenerate.setEnabled(true);
        btnHeal.setEnabled(true);
        log("📁  Selected: " + selectedRecording.toAbsolutePath());
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
        // Refresh tree so newly saved recording appears immediately
        refreshRecordingTree();
    }

    private void browseRecording() {
        Path startDir = Files.isDirectory(RECORDINGS_DIR)
                ? RECORDINGS_DIR.toAbsolutePath()
                : Path.of(".").toAbsolutePath();

        JFileChooser fc = new JFileChooser(startDir.toFile());
        fc.setDialogTitle("Select a recording file");
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Recording JSON (*.json)", "json"));

        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            selectedRecording = fc.getSelectedFile().toPath();
            String name = selectedRecording.getFileName().toString();
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

    /** Opens the autoqa-ide.html file in the system default browser. */
    private void openBrowserIde() {
        try {
            Path ide = Path.of("autoqa-ide.html").toAbsolutePath();
            if (!Files.exists(ide)) {
                log("autoqa-ide.html not found: " + ide);
                return;
            }
            Desktop.getDesktop().browse(ide.toUri());
            log("🌐  Opened Browser IDE: " + ide);
        } catch (Exception ex) {
            log("ERROR opening browser IDE: " + ex.getMessage());
        }
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

        try {
            Path rootJar = Path.of("autoqa.jar").toAbsolutePath();
            if (Files.exists(rootJar)) return rootJar.toString();

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
