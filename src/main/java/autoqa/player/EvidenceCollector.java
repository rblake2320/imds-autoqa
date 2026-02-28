package autoqa.player;

import autoqa.model.RecordedEvent;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures all available diagnostic artifacts when a player step fails.
 *
 * <p>Evidence is written to:
 * <pre>{evidenceBaseDir}/{sessionId}/{stepIndex}/</pre>
 * and consists of:
 * <ul>
 *   <li>{@code screenshot.png}  — full-page screenshot (if driver supports TakesScreenshot)</li>
 *   <li>{@code page-source.html} — raw DOM at time of failure</li>
 *   <li>{@code console.log}     — browser JS console entries</li>
 *   <li>{@code context.txt}     — URL, timestamp, event type, step index</li>
 * </ul>
 * Individual artifact failures are logged as warnings and do not abort collection.
 */
public class EvidenceCollector {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCollector.class);

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneId.of("UTC"));

    private final String evidenceBaseDir;

    public EvidenceCollector(String evidenceBaseDir) {
        this.evidenceBaseDir = evidenceBaseDir;
    }

    /**
     * Collects all available evidence for a failed step.
     *
     * @param driver    the WebDriver instance
     * @param sessionId the recording session ID (used as a sub-directory)
     * @param stepIndex zero-based step index
     * @param event     the event that failed (provides context for filenames and content)
     * @return the {@link Path} of the evidence directory created
     */
    public Path collect(WebDriver driver, String sessionId, int stepIndex, RecordedEvent event) {
        Path evidenceDir = buildEvidenceDir(sessionId, stepIndex);

        try {
            Files.createDirectories(evidenceDir);
            log.info("EvidenceCollector: writing artifacts to {}", evidenceDir);
        } catch (IOException e) {
            log.error("EvidenceCollector: cannot create evidence directory {}: {}", evidenceDir, e.getMessage());
            return evidenceDir;
        }

        captureScreenshot(driver, evidenceDir);
        capturePageSource(driver, evidenceDir);
        captureConsoleLogs(driver, evidenceDir);
        captureContext(driver, evidenceDir, stepIndex, event);

        return evidenceDir;
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private Path buildEvidenceDir(String sessionId, int stepIndex) {
        // Sanitise sessionId so it is safe as a directory name
        String safeName = sessionId.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return Paths.get(evidenceBaseDir, safeName, String.valueOf(stepIndex));
    }

    private void captureScreenshot(WebDriver driver, Path dir) {
        try {
            if (!(driver instanceof TakesScreenshot)) {
                log.warn("EvidenceCollector: driver does not support TakesScreenshot — skipping screenshot");
                return;
            }
            byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            Path target = dir.resolve("screenshot.png");
            Files.write(target, png);
            log.info("EvidenceCollector: screenshot saved to {}", target);
        } catch (Exception e) {
            log.warn("EvidenceCollector: failed to capture screenshot: {}", e.getMessage());
        }
    }

    private void capturePageSource(WebDriver driver, Path dir) {
        try {
            String source = driver.getPageSource();
            if (source == null) {
                log.warn("EvidenceCollector: getPageSource() returned null — skipping page-source");
                return;
            }
            Path target = dir.resolve("page-source.html");
            Files.writeString(target, source, StandardCharsets.UTF_8);
            log.info("EvidenceCollector: page source saved to {} ({} chars)", target, source.length());
        } catch (Exception e) {
            log.warn("EvidenceCollector: failed to capture page source: {}", e.getMessage());
        }
    }

    private void captureConsoleLogs(WebDriver driver, Path dir) {
        try {
            LogEntries entries = driver.manage().logs().get(LogType.BROWSER);
            if (entries == null) {
                log.warn("EvidenceCollector: browser log returned null — skipping console.log");
                return;
            }

            List<String> lines = new ArrayList<>();
            for (LogEntry entry : entries) {
                lines.add(String.format("[%s] [%s] %s",
                        TIMESTAMP_FMT.format(Instant.ofEpochMilli(entry.getTimestamp())),
                        entry.getLevel(),
                        entry.getMessage()));
            }

            Path target = dir.resolve("console.log");
            Files.write(target, lines, StandardCharsets.UTF_8);
            log.info("EvidenceCollector: {} console log line(s) saved to {}", lines.size(), target);
        } catch (Exception e) {
            // Not all drivers expose browser logs (e.g. Firefox GeckoDriver); treat as non-fatal
            log.warn("EvidenceCollector: failed to capture console logs (driver may not support this): {}",
                    e.getMessage());
        }
    }

    private void captureContext(WebDriver driver, Path dir, int stepIndex, RecordedEvent event) {
        try {
            String url = "(unknown)";
            try {
                url = driver.getCurrentUrl();
            } catch (Exception ignored) { }

            String now = TIMESTAMP_FMT.format(Instant.now());
            String eventType = event.getEventType() != null ? event.getEventType().name() : "(null)";
            String eventUrl = event.getUrl() != null ? event.getUrl() : "(null)";
            String element = event.getElement() != null ? event.getElement().toString() : "(no element)";

            List<String> lines = List.of(
                    "=== AutoQA Failure Evidence Context ===",
                    "Captured at   : " + now,
                    "Step index    : " + stepIndex,
                    "Event type    : " + eventType,
                    "Recorded URL  : " + eventUrl,
                    "Current URL   : " + url,
                    "Element       : " + element
            );

            Path target = dir.resolve("context.txt");
            Files.write(target, lines, StandardCharsets.UTF_8);
            log.info("EvidenceCollector: context saved to {}", target);
        } catch (Exception e) {
            log.warn("EvidenceCollector: failed to write context.txt: {}", e.getMessage());
        }
    }
}
