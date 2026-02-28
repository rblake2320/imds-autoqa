package autoqa.integration;

import autoqa.ai.AIConfig;
import autoqa.model.ElementInfo;
import autoqa.model.RecordedEvent;
import autoqa.model.RecordedEvent.EventType;
import autoqa.model.RecordedSession;
import autoqa.player.PlayerEngine;
import autoqa.player.PlayerEngine.PlaybackResult;
import io.qameta.allure.Description;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the AI self-healing chain.
 *
 * <p>Tests that {@link autoqa.ai.HealingInterceptor} + {@link autoqa.ai.LocatorHealer}
 * can recover from a {@link org.openqa.selenium.NoSuchElementException} by asking
 * the local Ollama LLM (qwen2.5-coder:32b) to suggest a corrected locator.
 *
 * <p>The test loads {@code test-page.html} in headless Edge, then attempts an INPUT
 * step against an element whose ID is intentionally misspelled ("usernme" instead
 * of "username"). With healing enabled the LLM should identify the correct element
 * from the live page HTML and the step should complete successfully.
 *
 * <p>Activate with:
 * <pre>mvn test -Phealing</pre>
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>Microsoft Edge installed on the machine</li>
 *   <li>Ollama reachable at {@code http://localhost:11434} with model
 *       {@code qwen2.5-coder:32b} pulled</li>
 * </ul>
 *
 * <p>When Ollama is not reachable or the required model is absent the tests
 * are skipped (not failed) via {@link SkipException}.
 */
@Feature("AI Self-Healing")
public class HealingIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HealingIntegrationTest.class);

    /** Path to the project-local bundled msedgedriver (for offline / air-gapped use). */
    private static final String LOCAL_DRIVER = ".drivers/msedgedriver.exe";

    /** Ollama base URL checked in {@link #checkOllamaAvailable()}. */
    private static final String OLLAMA_BASE_URL = "http://localhost:11434";

    /** Model that must be present in Ollama for the tests to run. */
    private static final String REQUIRED_MODEL = "qwen2.5-coder:32b";

    /**
     * Typo ID used to trigger NoSuchElementException — the real element has
     * {@code id="username"}, so "usernme" will definitely not be found by
     * LocatorResolver and must be healed by the LLM.
     */
    private static final String TYPO_ID = "usernme";

    private WebDriver driver;
    private boolean ollamaAvailable;
    private boolean browserAvailable;
    private String testPageUrl;

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Sets up Edge headless and resolves the test page URL.
     * If Ollama is not reachable the LLM-dependent tests will be skipped.
     * If Edge cannot be started all tests will be skipped.
     */
    @BeforeClass(alwaysRun = true)
    public void setUp() throws URISyntaxException {
        ollamaAvailable = checkOllamaAvailable();
        if (!ollamaAvailable) {
            log.warn("Ollama is not reachable at {} or model '{}' is not available — " +
                     "healing tests will be skipped", OLLAMA_BASE_URL, REQUIRED_MODEL);
        }

        // Resolve driver: explicit sysprop → local bundled driver → Selenium Manager
        configureDriver();

        try {
            EdgeOptions opts = new EdgeOptions();
            opts.addArguments("--headless=new");
            opts.addArguments("--no-sandbox");
            opts.addArguments("--disable-dev-shm-usage");
            opts.addArguments("--disable-gpu");

            driver = new EdgeDriver(opts);
            browserAvailable = true;
            testPageUrl = resolveTestPageUrl();
        } catch (Exception e) {
            browserAvailable = false;
            log.warn("Edge browser could not be started ({}): {} — all healing tests will be skipped",
                     e.getClass().getSimpleName(), e.getMessage());
        }
    }

    @AfterClass(alwaysRun = true)
    public void tearDown() {
        if (driver != null) {
            try { driver.quit(); } catch (Exception ignored) {}
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    /**
     * With AI healing enabled, a step that uses the typo ID "usernme" (instead of
     * the real "username") should be recovered by the LLM and the playback should
     * succeed.
     *
     * <p>Skipped automatically when Ollama is not reachable.
     */
    @Test(groups = "healing",
          description = "AI healing recovers a misspelled element ID via Ollama LLM")
    @Story("LLM-based locator healing")
    @Description("Clicks an element with a typo ID 'usernme'; healing should correct it to 'username'")
    public void healingEnabled_fixesBadLocator() {
        if (!browserAvailable) {
            throw new SkipException("Edge browser could not be started — skipping healing test");
        }
        if (!ollamaAvailable) {
            throw new SkipException("Ollama not available at " + OLLAMA_BASE_URL
                    + " or model '" + REQUIRED_MODEL + "' not found — skipping healing test");
        }

        RecordedSession session = buildSession(TYPO_ID, "healed-session");

        // AIConfig defaults read config.properties — ai.enabled=true, model=qwen2.5-coder:32b
        AIConfig aiConfig = new AIConfig();
        assertThat(aiConfig.isAiEnabled())
                .as("ai.enabled must be true in config.properties for this test to be meaningful")
                .isTrue();

        PlayerEngine engine = new PlayerEngine(driver, aiConfig);
        PlaybackResult result = engine.play(session);

        log.info("Healing-enabled playback result: {}", result);

        assertThat(result.isSuccess())
                .as("Playback with healing enabled should succeed even with typo ID. " +
                    "Failure reason: %s", result.getFailureReason())
                .isTrue();
    }

    /**
     * With AI healing disabled, the same typo ID should result in a failed
     * playback — confirming that healing is what rescued the enabled test.
     *
     * <p>This test does NOT require Ollama and always runs.
     */
    @Test(groups = "healing",
          description = "Without AI healing a misspelled element ID causes playback failure")
    @Story("LLM-based locator healing")
    @Description("Verifies baseline: same typo ID 'usernme' fails without healing enabled")
    public void healingDisabled_badLocator_fails() {
        if (!browserAvailable) {
            throw new SkipException("Edge browser could not be started — skipping baseline test");
        }

        RecordedSession session = buildSession(TYPO_ID, "no-heal-session");

        // Pass null AIConfig → HealingInterceptor is not wired → LocatorResolver only
        PlayerEngine engine = new PlayerEngine(driver, (AIConfig) null);
        PlaybackResult result = engine.play(session);

        log.info("Healing-disabled playback result: {}", result);

        assertThat(result.isSuccess())
                .as("Playback without healing should fail on a typo ID")
                .isFalse();
        assertThat(result.getFailureReason())
                .as("Failure reason should be set")
                .isNotBlank();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Builds a minimal two-step session:
     * <ol>
     *   <li>NAVIGATE to test-page.html</li>
     *   <li>INPUT into an element identified by {@code elementId} with value "test_heal"</li>
     * </ol>
     *
     * <p>Using INPUT (rather than CLICK) ensures the interaction goes through the
     * {@code findElement()} path in PlayerEngine, which routes via HealingInterceptor
     * when healing is enabled.
     */
    private RecordedSession buildSession(String elementId, String sessionId) {
        RecordedSession session = new RecordedSession();
        session.setSessionId(sessionId);
        session.setOsName(System.getProperty("os.name"));
        session.setRecordedBy("HealingIntegrationTest");

        // Step 1: navigate to the test page
        RecordedEvent nav = new RecordedEvent();
        nav.setEventType(EventType.NAVIGATE);
        nav.setTimestamp(Instant.now());
        nav.setUrl(testPageUrl);
        session.addEvent(nav);

        // Step 2: INPUT into an element using the given (potentially misspelled) ID.
        // The element will only be findable if healing corrects the ID to "username".
        ElementInfo el = new ElementInfo();
        el.setTagName("input");
        el.setId(elementId);
        // Provide a visible-text hint so the LLM can identify the right element.
        // Do NOT set name/css/xpath here — the only locator is the (possibly misspelled)
        // id, which is what forces the resolver to fail and healing to kick in.
        el.setText("Username");
        el.setType("text");

        autoqa.model.InputData inputData = new autoqa.model.InputData();
        inputData.setKeys("test_heal");

        RecordedEvent input = new RecordedEvent();
        input.setEventType(EventType.INPUT);
        input.setTimestamp(Instant.now());
        input.setUrl(testPageUrl);
        input.setElement(el);
        input.setInputData(inputData);
        session.addEvent(input);

        return session;
    }

    /**
     * Resolves {@code test-page.html} to a {@code file://} URI that Edge can load.
     * The file is expected to be on the test classpath (in {@code src/test/resources/}).
     */
    private String resolveTestPageUrl() throws URISyntaxException {
        URL pageUrl = getClass().getClassLoader().getResource("test-page.html");
        if (pageUrl == null) {
            throw new IllegalStateException(
                    "test-page.html not found on the test classpath. " +
                    "Ensure it exists in src/test/resources/.");
        }
        return pageUrl.toURI().toString();
    }

    /**
     * Probes Ollama at {@value #OLLAMA_BASE_URL} and checks that the required
     * model is available.  Returns {@code false} (rather than throwing) if
     * Ollama is unreachable or the model is absent, so callers can skip gracefully.
     */
    private static boolean checkOllamaAvailable() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_BASE_URL + "/api/tags"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Ollama /api/tags returned HTTP {}", response.statusCode());
                return false;
            }

            String body = response.body();
            boolean modelFound = body.contains(REQUIRED_MODEL);
            if (!modelFound) {
                log.warn("Ollama is running but model '{}' was not found in response: {}",
                        REQUIRED_MODEL, body.length() > 200 ? body.substring(0, 200) + "..." : body);
            }
            return modelFound;

        } catch (IOException | InterruptedException e) {
            log.warn("Could not reach Ollama at {}: {}", OLLAMA_BASE_URL, e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Sets {@code webdriver.edge.driver} if not already configured and the
     * project-local bundled driver exists.
     */
    private static void configureDriver() {
        if (System.getProperty("webdriver.edge.driver") != null) {
            return;
        }
        Path localDriver = Paths.get(LOCAL_DRIVER);
        if (Files.exists(localDriver)) {
            System.setProperty("webdriver.edge.driver", localDriver.toAbsolutePath().toString());
        }
    }
}
