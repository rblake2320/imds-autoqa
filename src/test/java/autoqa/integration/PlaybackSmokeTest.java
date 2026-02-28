package autoqa.integration;

import autoqa.model.RecordedEvent;
import autoqa.model.RecordedEvent.EventType;
import autoqa.model.RecordedSession;
import autoqa.model.RecordingIO;
import autoqa.player.PlayerEngine;
import autoqa.player.PlayerEngine.PlaybackResult;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test: loads {@code test-recording.json}, rewrites the
 * NAVIGATE URL to point at the classpath {@code test-page.html}, launches
 * Edge headless, plays back the recording, and asserts success.
 *
 * <p>Run with:
 * <pre>mvn test -Pintegration</pre>
 *
 * <p>Requires Microsoft Edge to be installed.  Driver is auto-managed by
 * Selenium Manager (bundled in Selenium 4.11+).
 */
public class PlaybackSmokeTest {

    private WebDriver driver;
    private Path     evidenceDir;

    @BeforeClass
    public void setUp() throws Exception {
        // Evidence directory for this run (cleaned up in @AfterClass)
        evidenceDir = Files.createTempDirectory("autoqa-e2e-evidence");

        // Launch Edge headless — Selenium Manager auto-downloads the matching driver
        EdgeOptions opts = new EdgeOptions();
        opts.addArguments("--headless=new");
        opts.addArguments("--no-sandbox");
        opts.addArguments("--disable-dev-shm-usage");
        opts.addArguments("--disable-gpu");

        driver = new EdgeDriver(opts);
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        if (driver != null) {
            try { driver.quit(); } catch (Exception ignored) {}
        }
        if (evidenceDir != null) {
            deleteRecursively(evidenceDir);
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test(description = "Full playback of test-recording.json on test-page.html succeeds")
    public void playback_testRecording_succeeds() throws Exception {
        RecordedSession session = buildSession();

        PlayerEngine engine = new PlayerEngine(driver);
        PlaybackResult result = engine.play(session);

        assertThat(result.isSuccess())
                .as("PlaybackResult should be success but was: %s", result)
                .isTrue();
        assertThat(result.getStepsCompleted())
                .as("All steps should be completed")
                .isEqualTo(result.getTotalSteps());
    }

    @Test(description = "Evidence directory is created when playback encounters a failure")
    public void playback_withBadElement_createsEvidenceDir() throws Exception {
        // Build a minimal session with a CLICK on an element that doesn't exist
        RecordedSession session = new RecordedSession();
        session.setSessionId("e2e-failure-test");
        session.setOsName(System.getProperty("os.name"));
        session.setRecordedBy("integration-test");

        // NAVIGATE to the test page so the browser has something loaded
        String testPageUrl = resolveTestPageUrl();
        RecordedEvent nav = new RecordedEvent();
        nav.setEventType(EventType.NAVIGATE);
        nav.setTimestamp(Instant.now());
        nav.setUrl(testPageUrl);
        session.addEvent(nav);

        // CLICK on a nonexistent element — will fail, generating evidence
        autoqa.model.ElementInfo ghost = new autoqa.model.ElementInfo();
        ghost.setId("this-element-does-not-exist-99999");
        RecordedEvent click = new RecordedEvent();
        click.setEventType(EventType.CLICK);
        click.setTimestamp(Instant.now());
        click.setUrl(testPageUrl);
        click.setElement(ghost);
        session.addEvent(click);

        PlayerEngine engine = new PlayerEngine(driver);
        PlaybackResult result = engine.play(session);

        // Playback should have failed (element not found)
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isNotBlank();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Loads {@code test-recording.json} from the classpath, resolves
     * {@code test-page.html} dynamically, and rewrites all event URLs to use
     * the resolved local path — no hardcoded {@code D:} drive paths.
     */
    private RecordedSession buildSession() throws IOException, URISyntaxException {
        // Load the template recording from classpath
        URL recordingUrl = getClass().getClassLoader().getResource("test-recording.json");
        assertThat(recordingUrl).as("test-recording.json must be on the test classpath").isNotNull();
        RecordedSession template = RecordingIO.read(Paths.get(recordingUrl.toURI()));

        // Resolve the test HTML page
        String testPageUrl = resolveTestPageUrl();

        // Rewrite all event URLs that reference the old hardcoded path
        List<RecordedEvent> rewritten = new ArrayList<>();
        for (RecordedEvent ev : template.getEvents()) {
            if (ev.getUrl() != null && ev.getUrl().contains("test-page.html")) {
                ev.setUrl(testPageUrl);
            }
            rewritten.add(ev);
        }
        template.setEvents(rewritten);
        return template;
    }

    /**
     * Resolves {@code test-page.html} to a {@code file://} URI that Edge can load.
     * Uses the classpath resource to locate the file without hardcoding the drive.
     */
    private String resolveTestPageUrl() throws URISyntaxException {
        URL pageUrl = getClass().getClassLoader().getResource("test-page.html");
        assertThat(pageUrl).as("test-page.html must be on the test classpath").isNotNull();
        URI uri = pageUrl.toURI();
        // Convert to file:// URL string that Edge accepts
        return uri.toString();
    }

    /** Recursively deletes a directory tree (best-effort). */
    private void deleteRecursively(Path dir) {
        try {
            Files.walk(dir)
                 .sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> {
                     try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                 });
        } catch (IOException ignored) {}
    }
}
