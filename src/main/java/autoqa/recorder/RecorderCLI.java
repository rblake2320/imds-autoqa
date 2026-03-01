package autoqa.recorder;

import autoqa.model.RecordingIO;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PicoCLI entry-point for the recorder subsystem.
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>{@code record start} — attaches JNativeHook, connects to Edge CDP,
 *       and streams events to a JSON file. Press Ctrl+C to finish.</li>
 *   <li>{@code record stop}  — informational; instructs the user to Ctrl+C
 *       the running process (no IPC is implemented in phase 3B).</li>
 *   <li>{@code record list}  — lists recordings in a directory with event counts.</li>
 * </ul>
 *
 * <p>This class is wired into {@code autoqa.cli.WrapperCLI} as a sub-command.
 */
@Command(
        name        = "record",
        description = "Record user interactions in Microsoft Edge",
        mixinStandardHelpOptions = true,
        subcommands = {
                RecorderCLI.StartCommand.class,
                RecorderCLI.StopCommand.class,
                RecorderCLI.ListCommand.class
        }
)
public class RecorderCLI implements Callable<Integer> {

    @Override
    public Integer call() {
        // No sub-command selected — print usage.
        CommandLine.usage(this, System.out);
        return 0;
    }

    // ── Sub-commands ──────────────────────────────────────────────────────

    /**
     * Starts a recording session. Blocks until the process receives SIGINT
     * (Ctrl+C), at which point the shutdown hook saves the recording.
     */
    @Command(
            name        = "start",
            description = "Start recording in Edge (Ctrl+C to stop and save)",
            mixinStandardHelpOptions = true
    )
    static class StartCommand implements Callable<Integer> {

        private static final Logger log = LoggerFactory.getLogger(StartCommand.class);

        @Option(
                names       = {"-p", "--port"},
                description = "Edge remote-debug port (default: 9222)",
                defaultValue = "9222"
        )
        int port;

        @Option(
                names       = {"-o", "--output"},
                description = "Directory for saved recordings (default: recordings)",
                defaultValue = "recordings"
        )
        String outputDir;

        @Option(
                names       = {"-u", "--url-filter"},
                description = "Only record events on URLs containing this substring"
        )
        String urlFilter;

        @Option(
                names       = {"--url"},
                description = "Open Edge at this URL before recording starts"
        )
        String targetUrl;

        @Option(
                names       = {"--name"},
                description = "Recording name / output file prefix (default: recording)"
        )
        String sessionName;

        @Override
        public Integer call() throws Exception {
            // Load config and apply CLI overrides
            RecorderConfig config = new RecorderConfig();
            config.setCdpPort(port);
            if (sessionName != null && !sessionName.isBlank()) {
                config.setSessionPrefix(sessionName);
            }

            // Launch Edge at the target URL when --url is given
            if (targetUrl != null && !targetUrl.isBlank()) {
                launchEdge(targetUrl, port);
                // Give Edge time to start and expose the CDP endpoint
                try { Thread.sleep(4000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                // Use URL as filter so only events on this site are recorded
                if (urlFilter == null) {
                    try {
                        java.net.URI uri = java.net.URI.create(targetUrl);
                        urlFilter = uri.getHost();
                    } catch (Exception ignored) { /* keep null filter */ }
                }
            }

            // A3: Create sentinel lock file so 'record stop' can signal graceful shutdown
            Path lockFile = Path.of(config.getOutputDir()).resolve(".autoqa-recording.lock");
            Files.createDirectories(lockFile.getParent());
            Files.writeString(lockFile, String.valueOf(ProcessHandle.current().pid()));

            RecordingSession session = new RecordingSession(config, urlFilter);

            Thread mainThread = Thread.currentThread();
            AtomicBoolean stoppedByLock = new AtomicBoolean(false);

            // Background thread: polls sentinel file every 500ms; when gone → signal main thread
            Thread watcher = new Thread(() -> {
                try {
                    while (Files.exists(lockFile)) {
                        Thread.sleep(500);
                    }
                    log.info("Sentinel lock file removed — signaling graceful recording stop");
                    stoppedByLock.set(true);
                    mainThread.interrupt();
                } catch (InterruptedException ie) {
                    // Ctrl+C interrupted the watcher — shutdown hook handles cleanup
                }
            }, "sentinel-watcher");
            watcher.setDaemon(true);
            watcher.start();

            // Shutdown hook: handles both Ctrl+C and sentinel-file stop
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                watcher.interrupt();
                try {
                    Path saved = session.stop();
                    if (saved != null) {
                        System.out.println("\nRecording saved: " + saved.toAbsolutePath());
                    } else {
                        System.out.println("\nRecording stopped (no events captured or already stopped).");
                    }
                    Files.deleteIfExists(lockFile);
                } catch (Exception e) {
                    System.err.println("Error saving recording: " + e.getMessage());
                    log.error("Shutdown hook error", e);
                }
            }, "recorder-shutdown"));

            session.start();
            System.out.println("Recording started. Interact with Edge, then press Ctrl+C or run 'autoqa record stop'.");
            System.out.printf("  CDP port   : %d%n", port);
            System.out.printf("  Output dir : %s%n", Path.of(config.getOutputDir()).toAbsolutePath());
            System.out.printf("  Lock file  : %s%n", lockFile.toAbsolutePath());
            if (urlFilter != null) {
                System.out.printf("  URL filter : *%s*%n", urlFilter);
            }

            // Block the main thread; sentinel watcher or Ctrl+C will interrupt it
            try {
                Thread.currentThread().join();
            } catch (InterruptedException e) {
                if (stoppedByLock.get()) {
                    // Sentinel file was deleted by 'record stop' — exit cleanly (shutdown hook runs)
                    System.exit(0);
                }
                // else: Ctrl+C, shutdown hook runs automatically
            }
            return 0;
        }

        /**
         * Launches Microsoft Edge with remote debugging enabled on the given port
         * and navigates to {@code url}.  Searches common installation paths.
         */
        private static void launchEdge(String url, int port) {
            String[] candidates = {
                "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
                "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
                System.getenv("LOCALAPPDATA") != null
                    ? System.getenv("LOCALAPPDATA") + "/Microsoft/Edge/Application/msedge.exe"
                    : ""
            };
            // Use a dedicated temp profile so the debug-port flag is always honoured
            // even when another Edge window is already open.
            String userDataDir = System.getProperty("java.io.tmpdir") + "/autoqa-edge-debug-" + port;

            for (String path : candidates) {
                if (path == null || path.isBlank()) continue;
                File exe = new File(path.replace('/', java.io.File.separatorChar));
                if (exe.exists()) {
                    try {
                        new ProcessBuilder(
                            exe.getAbsolutePath(),
                            "--remote-debugging-port=" + port,
                            "--user-data-dir=" + userDataDir,
                            "--no-first-run",
                            "--no-default-browser-check",
                            "--disable-background-mode",
                            "--disable-popup-blocking",
                            url
                        ).start();
                        log.info("Launched Edge → {} (CDP port {}, profile: {})", url, port, userDataDir);
                        return;
                    } catch (Exception e) {
                        log.warn("Failed to launch Edge from {}: {}", path, e.getMessage());
                    }
                }
            }
            log.warn("msedge.exe not found — open Edge manually with: " +
                     "msedge.exe --remote-debugging-port={} {}", port, url);
            System.out.println("WARNING: Could not launch Edge automatically.");
            System.out.println("Please open Edge and navigate to: " + url);
            System.out.println("Make sure Edge is started with: --remote-debugging-port=" + port);
        }
    }

    /**
     * Stops an active recording by deleting the sentinel lock file.
     *
     * <p>The running {@code record start} process watches for the lock file to
     * disappear and triggers a graceful save when it does.  This command is
     * Windows-compatible (no POSIX signals required).
     */
    @Command(
            name        = "stop",
            description = "Stop an active recording by removing the sentinel lock file",
            mixinStandardHelpOptions = true
    )
    static class StopCommand implements Callable<Integer> {

        @Option(
                names       = {"-d", "--dir"},
                description = "Recordings directory (default: recordings)",
                defaultValue = "recordings"
        )
        String dir;

        @Override
        public Integer call() throws Exception {
            Path lockFile = Path.of(dir, ".autoqa-recording.lock");
            if (Files.deleteIfExists(lockFile)) {
                System.out.println("Stop signal sent. The recording will be saved automatically.");
                System.out.println("(Lock file removed: " + lockFile.toAbsolutePath() + ")");
            } else {
                System.out.println("No active recording lock file found at: " + lockFile.toAbsolutePath());
                System.out.println("The recording may have already stopped, or press Ctrl+C in the recording terminal.");
            }
            return 0;
        }
    }

    /**
     * Lists all {@code .json} recording files in a directory, showing
     * each file name and its event count.
     */
    @Command(
            name        = "list",
            description = "List saved recordings and their event counts",
            mixinStandardHelpOptions = true
    )
    static class ListCommand implements Callable<Integer> {

        private static final Logger log = LoggerFactory.getLogger(ListCommand.class);

        @Option(
                names       = {"-d", "--dir"},
                description = "Recordings directory to list (default: recordings)",
                defaultValue = "recordings"
        )
        String dir;

        @Override
        public Integer call() throws Exception {
            Path recordingsDir = Path.of(dir);
            if (!Files.exists(recordingsDir)) {
                System.out.println("No recordings directory found: "
                        + recordingsDir.toAbsolutePath());
                return 1;
            }

            System.out.printf("%-50s %10s%n", "File", "Events");
            System.out.println("-".repeat(62));

            long[] totals = {0L, 0L}; // [fileCount, eventCount]

            Files.list(recordingsDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            var recordedSession = RecordingIO.read(p);
                            int count = recordedSession.getEventCount();
                            System.out.printf("%-50s %10d%n",
                                    p.getFileName(), count);
                            totals[0]++;
                            totals[1] += count;
                        } catch (Exception e) {
                            log.debug("Could not read recording {}: {}", p, e.getMessage());
                            System.out.printf("%-50s %10s%n",
                                    p.getFileName(), "ERROR");
                        }
                    });

            if (totals[0] == 0) {
                System.out.println("No recordings found in: " + recordingsDir.toAbsolutePath());
            } else {
                System.out.println("-".repeat(62));
                System.out.printf("%-50s %10d%n",
                        totals[0] + " file(s) total", totals[1]);
            }
            return 0;
        }
    }
}
