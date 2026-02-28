package autoqa.keyword;

import autoqa.model.ObjectRepository;
import autoqa.player.AutoQAException;
import autoqa.player.PlayerConfig;
import autoqa.player.WaitStrategy;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Keyword-driven test runner — the IMDS AutoQA equivalent of UFT One's
 * Keyword View test execution.
 *
 * <p>Loads a JSON keyword test file, resolves each step through the
 * {@link KeywordLibrary}, and executes it against the WebDriver session.
 *
 * <h3>Keyword Test File Format</h3>
 * <pre>{@code
 * [
 *   {
 *     "keyword": "navigate",
 *     "params": { "url": "https://example.com/login" },
 *     "description": "Open login page"
 *   },
 *   {
 *     "keyword": "typeText",
 *     "target": "usernameField",
 *     "params": { "value": "admin" }
 *   },
 *   {
 *     "keyword": "click",
 *     "target": "loginButton"
 *   },
 *   {
 *     "keyword": "verifyText",
 *     "target": "welcomeHeader",
 *     "params": { "expected": "Welcome", "mode": "contains" }
 *   }
 * ]
 * }</pre>
 *
 * <p>Usage:
 * <pre>{@code
 * KeywordEngine engine = new KeywordEngine(driver, or);
 * KeywordEngine.RunResult result = engine.run(Path.of("login-test.json"));
 * }</pre>
 */
public class KeywordEngine {

    private static final Logger log = LoggerFactory.getLogger(KeywordEngine.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebDriver      driver;
    private final KeywordLibrary library;

    public KeywordEngine(WebDriver driver, ObjectRepository or) {
        PlayerConfig config = new PlayerConfig();
        WaitStrategy wait   = new WaitStrategy(driver, config.getExplicitWaitSec());
        this.driver  = driver;
        this.library = new KeywordLibrary(driver, or, wait);
    }

    /** Constructor for when no Object Repository is needed. */
    public KeywordEngine(WebDriver driver) {
        this(driver, null);
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    /**
     * Loads a keyword test from a JSON file.  The file must contain a JSON
     * array of {@link KeywordStepJson} objects.
     *
     * @throws IOException if the file cannot be read
     * @throws AutoQAException if the JSON is malformed
     */
    public List<KeywordAction> load(Path file) throws IOException {
        List<KeywordStepJson> steps = MAPPER.readValue(file.toFile(),
                new TypeReference<>() {});
        List<KeywordAction> actions = new ArrayList<>();
        for (KeywordStepJson step : steps) {
            actions.add(new KeywordAction(
                    step.keyword, step.target, step.params, step.description));
        }
        log.info("Loaded {} keyword steps from {}", actions.size(), file.getFileName());
        return Collections.unmodifiableList(actions);
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    /**
     * Runs all keyword steps from a JSON file.
     *
     * @return a {@link RunResult} summarising pass/fail per step
     */
    public RunResult run(Path file) throws IOException {
        return runSteps(load(file));
    }

    /**
     * Runs a pre-loaded list of keyword actions.
     *
     * @return a {@link RunResult} summarising pass/fail per step
     */
    public RunResult runSteps(List<KeywordAction> steps) {
        int total = steps.size();
        log.info("Starting keyword test — {} step(s)", total);

        for (int i = 0; i < total; i++) {
            KeywordAction step = steps.get(i);
            try {
                library.execute(driver, step);
                log.info("Step {}/{} PASS: {}", i + 1, total, step);
            } catch (AutoQAException | AssertionError e) {
                String reason = "Step " + (i + 1) + "/" + total
                        + " FAIL [" + step.getKeyword() + "]: " + e.getMessage();
                log.error(reason);
                return new RunResult(false, i, total, reason);
            } catch (Exception e) {
                String reason = "Step " + (i + 1) + "/" + total
                        + " ERROR [" + step.getKeyword() + "]: " + e.getMessage();
                log.error(reason, e);
                return new RunResult(false, i, total, reason);
            }
        }

        log.info("Keyword test completed — {}/{} steps passed", total, total);
        return new RunResult(true, total, total, null);
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /** JSON deserialization DTO for one keyword step. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeywordStepJson {
        @JsonProperty("keyword")     public String              keyword;
        @JsonProperty("target")      public String              target;
        @JsonProperty("params")      public Map<String, String> params;
        @JsonProperty("description") public String              description;
    }

    /** Immutable result of a keyword test run. */
    public static final class RunResult {
        private final boolean success;
        private final int     stepsCompleted;
        private final int     totalSteps;
        private final String  failureReason;

        public RunResult(boolean success, int stepsCompleted, int totalSteps, String failureReason) {
            this.success        = success;
            this.stepsCompleted = stepsCompleted;
            this.totalSteps     = totalSteps;
            this.failureReason  = failureReason;
        }

        public boolean isSuccess()       { return success; }
        public int  getStepsCompleted()  { return stepsCompleted; }
        public int  getTotalSteps()      { return totalSteps; }
        public String getFailureReason() { return failureReason; }

        @Override
        public String toString() {
            return success
                    ? String.format("RunResult{SUCCESS, %d/%d steps}", stepsCompleted, totalSteps)
                    : String.format("RunResult{FAILED at step %d/%d: %s}",
                                    stepsCompleted, totalSteps, failureReason);
        }
    }
}
