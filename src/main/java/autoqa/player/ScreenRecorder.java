package autoqa.player;

import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Captures periodic screenshots during playback and saves them as PNG files
 * in a session-specific output directory.
 *
 * <p>Equivalent to UFT One's <em>Screen Recorder</em> option in Run Settings,
 * which records a video of the test run.  IMDS AutoQA saves individual frames
 * (PNG) which can be post-processed into a video with any standard tool
 * (ffmpeg, gifski, etc.).  Each step can also trigger a manual capture via
 * {@link #captureStep(int, String)}.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ScreenRecorder rec = new ScreenRecorder(driver, outputDir, 500); // 2fps
 * rec.start("session-001");
 * // ... run playback ...
 * rec.captureStep(3, "Click login button");
 * // ... more steps ...
 * List<Path> frames = rec.stop();
 * }</pre>
 */
public class ScreenRecorder {

    private static final Logger log = LoggerFactory.getLogger(ScreenRecorder.class);

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("HHmmss_SSS").withZone(ZoneId.systemDefault());

    private final WebDriver driver;
    private final Path      outputDir;
    private final long      intervalMs;

    private ScheduledExecutorService executor;
    private ScheduledFuture<?>       task;
    private Path                     sessionDir;
    private final AtomicInteger      frameCounter = new AtomicInteger(0);
    private final List<Path>         capturedFiles = new ArrayList<>();

    /**
     * Creates a ScreenRecorder.
     *
     * @param driver     WebDriver session to screenshot
     * @param outputDir  parent directory for all frame folders
     * @param intervalMs milliseconds between automatic frames (0 = disable auto-capture)
     */
    public ScreenRecorder(WebDriver driver, Path outputDir, long intervalMs) {
        this.driver     = driver;
        this.outputDir  = outputDir;
        this.intervalMs = intervalMs;
    }

    /** Convenience constructor with default 500 ms interval. */
    public ScreenRecorder(WebDriver driver, Path outputDir) {
        this(driver, outputDir, 500L);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Starts recording.  Creates a session sub-directory and begins periodic
     * frame capture if {@code intervalMs > 0}.
     *
     * @param sessionId used as the sub-directory name
     */
    public synchronized void start(String sessionId) {
        if (task != null) {
            log.warn("ScreenRecorder already started — ignoring duplicate start()");
            return;
        }

        sessionDir = outputDir.resolve(sessionId + "_" + Instant.now().toEpochMilli());
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            log.error("Cannot create screen-recording directory: {}", sessionDir, e);
            return;
        }

        frameCounter.set(0);
        capturedFiles.clear();
        log.info("ScreenRecorder started → {}", sessionDir);

        if (intervalMs > 0) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "screen-recorder");
                t.setDaemon(true);
                return t;
            });
            task = executor.scheduleAtFixedRate(
                    this::captureAuto, 0, intervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Stops recording, shuts down the periodic capture, and returns the
     * ordered list of all captured PNG files.
     */
    public synchronized List<Path> stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (executor != null) {
            executor.shutdown();
            try { executor.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            executor = null;
        }
        log.info("ScreenRecorder stopped — {} frames in {}", capturedFiles.size(), sessionDir);
        return Collections.unmodifiableList(new ArrayList<>(capturedFiles));
    }

    // ── Manual step capture ───────────────────────────────────────────────────

    /**
     * Takes a labelled screenshot for the given step.  Call this from the
     * player after each step completes to produce a frame per test step.
     *
     * @param stepIndex  0-based step number
     * @param stepLabel  human-readable description (used in filename)
     * @return the saved file path, or {@code null} if capture failed
     */
    public Path captureStep(int stepIndex, String stepLabel) {
        if (sessionDir == null) {
            log.warn("ScreenRecorder not started — skipping step capture");
            return null;
        }
        String safe  = stepLabel == null ? "" : stepLabel.replaceAll("[^A-Za-z0-9_\\-]", "_");
        String name  = String.format("step_%04d_%s_%s.png", stepIndex, safe,
                TS_FMT.format(Instant.now()));
        return saveFrame(name);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void captureAuto() {
        int idx  = frameCounter.getAndIncrement();
        String name = String.format("frame_%06d_%s.png", idx, TS_FMT.format(Instant.now()));
        saveFrame(name);
    }

    private Path saveFrame(String filename) {
        if (!(driver instanceof TakesScreenshot ts)) return null;
        try {
            byte[] png  = ts.getScreenshotAs(OutputType.BYTES);
            Path   dest = sessionDir.resolve(filename);
            Files.write(dest, png);
            synchronized (capturedFiles) {
                capturedFiles.add(dest);
            }
            return dest;
        } catch (Exception e) {
            log.debug("ScreenRecorder: frame capture failed — {}", e.getMessage());
            return null;
        }
    }
}
