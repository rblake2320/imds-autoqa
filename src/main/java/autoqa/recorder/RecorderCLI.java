package autoqa.recorder;

import autoqa.model.RecordingIO;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

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

        @Override
        public Integer call() throws Exception {
            // RecorderConfig loads from classpath config.properties.
            // --url-filter is passed directly to RecordingSession at runtime so
            // the user can focus recording on a specific URL without editing config.
            RecorderConfig config = new RecorderConfig();
            RecordingSession session = new RecordingSession(config, urlFilter);

            // Shutdown hook: invoked by JVM when Ctrl+C is received.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Path saved = session.stop();
                    if (saved != null) {
                        System.out.println("\nRecording saved: " + saved.toAbsolutePath());
                    } else {
                        System.out.println("\nRecording stopped (no events captured or already stopped).");
                    }
                } catch (Exception e) {
                    System.err.println("Error saving recording: " + e.getMessage());
                    log.error("Shutdown hook error", e);
                }
            }, "recorder-shutdown"));

            session.start();
            System.out.println("Recording started. Interact with Edge, then press Ctrl+C to stop.");
            System.out.printf("  CDP port   : %d (override via recorder.cdp.port in config.properties)%n", port);
            System.out.printf("  Output dir : %s (override via recorder.output.dir in config.properties)%n",
                    Path.of(config.getOutputDir()).toAbsolutePath());
            if (urlFilter != null) {
                System.out.printf("  URL filter : *%s* (only URLs containing this string will be recorded)%n",
                        urlFilter);
            }

            // Block the main thread indefinitely; the shutdown hook handles exit.
            Thread.currentThread().join();
            return 0;
        }
    }

    /**
     * Informational command: there is no IPC stop mechanism in phase 3B.
     * Users should send Ctrl+C to the recording process.
     */
    @Command(
            name        = "stop",
            description = "Stop an active recording (send Ctrl+C to the recording process)",
            mixinStandardHelpOptions = true
    )
    static class StopCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            System.out.println(
                    "To stop a recording, switch to the terminal running 'record start' " +
                    "and press Ctrl+C.\nThe recording will be saved automatically.");
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
