package autoqa.player;

import autoqa.model.ElementInfo;
import autoqa.model.RecordedEvent;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.logging.LogEntries;
import org.openqa.selenium.logging.LogEntry;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.logging.Logs;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EvidenceCollector}.
 *
 * <p>Uses a temporary directory so no real browser is needed.
 * The WebDriver is mocked to return canned screenshot bytes, page source, etc.
 */
public class EvidenceCollectorTest {

    /** A combined mock that implements both WebDriver and TakesScreenshot. */
    interface ScreenshottingDriver extends WebDriver, TakesScreenshot { }

    @Mock private ScreenshottingDriver driver;
    @Mock private WebDriver.Options options;
    @Mock private Logs logs;

    private AutoCloseable mocks;
    private Path tempDir;
    private EvidenceCollector collector;

    @BeforeMethod
    public void setUp() throws IOException {
        mocks = MockitoAnnotations.openMocks(this);
        tempDir = Files.createTempDirectory("evidence-test-");
        collector = new EvidenceCollector(tempDir.toString());

        // Standard driver wiring
        when(driver.manage()).thenReturn(options);
        when(options.logs()).thenReturn(logs);
        when(driver.getCurrentUrl()).thenReturn("https://example.com/test");
    }

    @AfterMethod
    public void tearDown() throws Exception {
        mocks.close();
        deleteDirectory(tempDir);
    }

    // ── Evidence directory creation ───────────────────────────────────────

    @Test
    public void collect_createsEvidenceDirectory() {
        stubScreenshot(new byte[]{0x50, 0x4E, 0x47});  // minimal PNG header bytes
        stubPageSource("<html>test</html>");
        stubConsoleLogs(List.of());

        RecordedEvent event = makeEvent("CLICK", "https://example.com");
        Path evidenceDir = collector.collect(driver, "session-abc", 0, event);

        assertThat(evidenceDir).isDirectory();
        // Should be inside our temp dir
        assertThat(evidenceDir.toString()).contains(tempDir.toString());
    }

    // ── Screenshot ────────────────────────────────────────────────────────

    @Test
    public void collect_writesScreenshotPng() throws IOException {
        byte[] pngBytes = {(byte) 0x89, 0x50, 0x4E, 0x47};  // PNG magic bytes
        stubScreenshot(pngBytes);
        stubPageSource("<html/>");
        stubConsoleLogs(List.of());

        Path evidenceDir = collector.collect(driver, "session-png", 2, makeEvent("CLICK", "http://a.com"));

        Path screenshot = evidenceDir.resolve("screenshot.png");
        assertThat(screenshot).exists();
        assertThat(Files.readAllBytes(screenshot)).isEqualTo(pngBytes);
    }

    // ── Page source ───────────────────────────────────────────────────────

    @Test
    public void collect_writesPageSource() throws IOException {
        String html = "<!DOCTYPE html><html><head><title>T</title></head><body>hello</body></html>";
        stubScreenshot(new byte[0]);
        stubPageSource(html);
        stubConsoleLogs(List.of());

        Path evidenceDir = collector.collect(driver, "session-src", 1, makeEvent("INPUT", "http://b.com"));

        Path pageSource = evidenceDir.resolve("page-source.html");
        assertThat(pageSource).exists();
        assertThat(Files.readString(pageSource, StandardCharsets.UTF_8)).isEqualTo(html);
    }

    // ── Console log ───────────────────────────────────────────────────────

    @Test
    public void collect_writesConsoleLog_withEntries() throws IOException {
        List<LogEntry> entries = List.of(
                new LogEntry(Level.SEVERE, 1_700_000_000_000L, "Error: something broke"),
                new LogEntry(Level.WARNING, 1_700_000_001_000L, "Warning: deprecation")
        );
        stubScreenshot(new byte[0]);
        stubPageSource("<html/>");
        stubConsoleLogs(entries);

        Path evidenceDir = collector.collect(driver, "session-log", 3, makeEvent("CLICK", "http://c.com"));

        Path consoleLog = evidenceDir.resolve("console.log");
        assertThat(consoleLog).exists();

        String content = Files.readString(consoleLog, StandardCharsets.UTF_8);
        assertThat(content).contains("Error: something broke");
        assertThat(content).contains("Warning: deprecation");
        assertThat(content).contains("SEVERE");
        assertThat(content).contains("WARNING");
    }

    @Test
    public void collect_writesConsoleLog_emptyWhenNoEntries() throws IOException {
        stubScreenshot(new byte[0]);
        stubPageSource("<html/>");
        stubConsoleLogs(List.of());

        Path evidenceDir = collector.collect(driver, "session-empty-log", 0, makeEvent("NAVIGATE", "http://d.com"));

        Path consoleLog = evidenceDir.resolve("console.log");
        assertThat(consoleLog).exists();
        // File exists but content should be empty or just whitespace
        String content = Files.readString(consoleLog, StandardCharsets.UTF_8).trim();
        assertThat(content).isEmpty();
    }

    // ── Context file ──────────────────────────────────────────────────────

    @Test
    public void collect_writesContextFile_withUrlAndEventType() throws IOException {
        stubScreenshot(new byte[0]);
        stubPageSource("<html/>");
        stubConsoleLogs(List.of());

        RecordedEvent event = makeEvent("SELECT", "https://example.com/page");
        Path evidenceDir = collector.collect(driver, "session-ctx", 5, event);

        Path contextFile = evidenceDir.resolve("context.txt");
        assertThat(contextFile).exists();

        String content = Files.readString(contextFile, StandardCharsets.UTF_8);
        assertThat(content).contains("SELECT");
        assertThat(content).contains("5");
        assertThat(content).contains("https://example.com/page");
        assertThat(content).contains("https://example.com/test"); // driver.getCurrentUrl()
    }

    // ── Resilience ────────────────────────────────────────────────────────

    /**
     * If getScreenshotAs() throws, the collector should still write the other
     * artifacts and not propagate the exception.
     */
    @Test
    public void collect_continuesCollecting_whenScreenshotFails() throws IOException {
        when(driver.getScreenshotAs(OutputType.BYTES))
                .thenThrow(new RuntimeException("screenshot driver error"));
        stubPageSource("<html>partial</html>");
        stubConsoleLogs(List.of());

        Path evidenceDir = collector.collect(driver, "session-fail-ss", 0, makeEvent("CLICK", "http://e.com"));

        // Page source should still be written even though screenshot failed
        assertThat(evidenceDir.resolve("page-source.html")).exists();
        assertThat(evidenceDir.resolve("context.txt")).exists();
        // Screenshot should NOT exist (it failed)
        assertThat(evidenceDir.resolve("screenshot.png")).doesNotExist();
    }

    /**
     * If getPageSource() throws, the collector should still write the other artifacts.
     */
    @Test
    public void collect_continuesCollecting_whenPageSourceFails() throws IOException {
        stubScreenshot(new byte[]{1, 2, 3});
        when(driver.getPageSource()).thenThrow(new RuntimeException("page source unavailable"));
        stubConsoleLogs(List.of());

        Path evidenceDir = collector.collect(driver, "session-fail-ps", 0, makeEvent("HOVER", "http://f.com"));

        assertThat(evidenceDir.resolve("screenshot.png")).exists();
        assertThat(evidenceDir.resolve("context.txt")).exists();
        assertThat(evidenceDir.resolve("page-source.html")).doesNotExist();
    }

    /**
     * Step-index-based sub-directory naming — each step gets its own directory.
     */
    @Test
    public void collect_createsDistinctDirectoriesPerStep() {
        stubScreenshot(new byte[0]);
        stubPageSource("<html/>");
        stubConsoleLogs(List.of());

        Path dir0 = collector.collect(driver, "multi", 0, makeEvent("CLICK", "http://a.com"));
        Path dir1 = collector.collect(driver, "multi", 1, makeEvent("INPUT", "http://a.com"));
        Path dir7 = collector.collect(driver, "multi", 7, makeEvent("SCROLL", "http://a.com"));

        assertThat(dir0).isNotEqualTo(dir1);
        assertThat(dir1).isNotEqualTo(dir7);
        assertThat(dir0.getFileName().toString()).isEqualTo("0");
        assertThat(dir1.getFileName().toString()).isEqualTo("1");
        assertThat(dir7.getFileName().toString()).isEqualTo("7");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void stubScreenshot(byte[] bytes) {
        when(driver.getScreenshotAs(OutputType.BYTES)).thenReturn(bytes);
    }

    private void stubPageSource(String html) {
        when(driver.getPageSource()).thenReturn(html);
    }

    private void stubConsoleLogs(List<LogEntry> entries) {
        LogEntries logEntries = new LogEntries(entries);
        when(logs.get(LogType.BROWSER)).thenReturn(logEntries);
    }

    private RecordedEvent makeEvent(String typeName, String url) {
        RecordedEvent event = new RecordedEvent();
        event.setEventType(RecordedEvent.EventType.valueOf(typeName));
        event.setUrl(url);
        event.setTimestamp(Instant.now());

        ElementInfo ei = new ElementInfo();
        ei.setTagName("button");
        ei.setId("test-btn");
        event.setElement(ei);

        return event;
    }

    private void deleteDirectory(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(p -> {
                      try { Files.delete(p); } catch (IOException ignored) { }
                  });
        }
    }
}
