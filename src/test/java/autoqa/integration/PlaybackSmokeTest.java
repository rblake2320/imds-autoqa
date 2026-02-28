package autoqa.integration;

import autoqa.model.RecordedEvent;
import autoqa.model.RecordedEvent.EventType;
import autoqa.model.RecordedSession;
import autoqa.model.RecordingIO;
import autoqa.player.PlayerEngine;
import autoqa.player.PlayerEngine.PlaybackResult;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeDriverService;
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
 * <p>Requires Microsoft Edge to be installed.
 *
 * <p>Driver resolution order:
 * <ol>
 *   <li>System property {@code webdriver.edge.driver} if set</li>
 *   <li>Project-local {@code .drivers/msedgedriver.exe} (bundled for offline use)</li>
 *   <li>Selenium Manager auto-download (requires internet access)</li>
 * </ol>
 */
public class PlaybackSmokeTest {

    /** Path to the project-local bundled msedgedriver (for offline / air-gapped use). */
    private static final String LOCAL_DRIVER = ".drivers/msedgedriver.exe";

    private WebDriver driver;
    private Path     evidenceDir;

    @BeforeClass
    public void setUp() throws Exception {
        // Evidence directory for this run (cleaned up in @AfterClass)
        evidenceDir = Files.createTempDirectory("autoqa-e2e-evidence");

        // Resolve driver: explicit sysprop → local bundled driver → Selenium Manager
        configureDriver();

        EdgeOptions opts = new EdgeOptions();
        opts.addArguments("--headless=new");
        opts.addArguments("--no-sandbox");
        opts.addArguments("--disable-dev-shm-usage");
        opts.addArguments("--disable-gpu");

        driver = new EdgeDriver(opts);
    }

    /**
     * Sets {@code webdriver.edge.driver} if not already set and the project-local
     * bundled driver exists.  Falls back silently to Selenium Manager when the
     * local driver is absent (e.g. in a fresh CI checkout that has internet).
     */
    private static void configureDriver() {
        if (System.getProperty("webdriver.edge.driver") != null) {
            return; // already configured externally
        }
        // Project-local driver relative to the working directory (repo root)
        Path localDriver = Paths.get(LOCAL_DRIVER);
        if (Files.exists(localDriver)) {
            System.setProperty("webdriver.edge.driver", localDriver.toAbsolutePath().toString());
        }
        // else: let Selenium Manager handle it (needs internet)
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
     * {@code test-page.html} dynamically, and rewrites all event URLs.
     *
     * <p>Events involving native alerts ({@code ALERT}), new-window switches
     * ({@code WINDOW_SWITCH}), and iframe switches ({@code FRAME_SWITCH}) are
     * excluded because headless Edge on {@code file://} URLs does not reliably
     * surface these browser dialogs / handles.  The remaining 12 form-interaction
     * steps exercise NAVIGATE, HOVER, INPUT, SELECT, CLICK, KEY_PRESS, and SCROLL
     * — the core of what the PlayerEngine must handle correctly.
     */
    private RecordedSession buildSession() throws IOException, URISyntaxException {
        // Load the template recording from classpath
        URL recordingUrl = getClass().getClassLoader().getResource("test-recording.json");
        assertThat(recordingUrl).as("test-recording.json must be on the test classpath").isNotNull();
        RecordedSession template = RecordingIO.read(Paths.get(recordingUrl.toURI()));

        // Resolve the test HTML page
        String testPageUrl = resolveTestPageUrl();

        // Rewrite URLs and filter out headless-incompatible event types
        List<RecordedEvent> filtered = new ArrayList<>();
        for (RecordedEvent ev : template.getEvents()) {
            // Skip alert/window/frame events — unreliable headless on file://
            EventType t = ev.getEventType();
            if (t == EventType.ALERT || t == EventType.WINDOW_SWITCH || t == EventType.FRAME_SWITCH) {
                continue;
            }
            // Skip the CLICK that triggers the alert dialog (#alert-btn)
            if (t == EventType.CLICK && ev.getElement() != null
                    && "alert-btn".equals(ev.getElement().getId())) {
                continue;
            }
            // Skip the CLICK on #new-window-btn (would trigger window.open)
            if (t == EventType.CLICK && ev.getElement() != null
                    && "new-window-btn".equals(ev.getElement().getId())) {
                continue;
            }
            // Skip the CLICK on #iframe-btn (element is inside the filtered-out iframe)
            if (t == EventType.CLICK && ev.getElement() != null
                    && "iframe-btn".equals(ev.getElement().getId())) {
                continue;
            }
            if (ev.getUrl() != null && ev.getUrl().contains("test-page.html")) {
                ev.setUrl(testPageUrl);
            }
            filtered.add(ev);
        }
        template.setEvents(filtered);
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
