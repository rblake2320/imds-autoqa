package autoqa.ai;

import autoqa.player.PlayerEngine.PlaybackResult;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * AI-powered test failure root cause analyzer — the IMDS AutoQA equivalent
 * of Functionize/ACCELQ/testRigor's AI failure analysis, which is a capability
 * that goes well beyond anything UFT One offers.
 *
 * <p>When a playback step fails, this class:
 * <ol>
 *   <li>Captures the current page URL + title</li>
 *   <li>Takes a screenshot of the failure state</li>
 *   <li>Captures a DOM snapshot (page source)</li>
 *   <li>Sends everything to the configured LLM with a structured prompt</li>
 *   <li>Returns a structured {@link FailureAnalysis} explaining the root cause
 *       and suggesting a fix</li>
 * </ol>
 *
 * <p>This gives every test failure an instant AI-generated explanation, turning
 * cryptic "NoSuchElementException" errors into actionable root cause reports.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * FailureAnalyzer analyzer = new FailureAnalyzer(new AIConfig());
 * FailureAnalysis analysis = analyzer.analyze(driver, result, exception);
 * System.out.println(analysis.getRootCause());
 * System.out.println(analysis.getSuggestedFix());
 * }</pre>
 */
public class FailureAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(FailureAnalyzer.class);

    private static final int MAX_DOM_CHARS   = 8_000;
    private static final int MAX_ERROR_CHARS = 1_000;

    private final LLMClient llmClient;

    public FailureAnalyzer(AIConfig config) {
        this.llmClient = config.createLLMClient();
    }

    public FailureAnalyzer(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    // ── Analysis ─────────────────────────────────────────────────────────────

    /**
     * Analyses a playback failure and returns a structured root cause report.
     *
     * @param driver    WebDriver session at the point of failure (for context capture)
     * @param result    the failed playback result from PlayerEngine
     * @param exception the exception that caused the failure (may be null)
     * @return a {@link FailureAnalysis} with root cause and suggested fix
     */
    public FailureAnalysis analyze(WebDriver driver, PlaybackResult result, Throwable exception) {
        log.info("Starting AI failure analysis for step {}/{}",
                result.getStepsCompleted(), result.getTotalSteps());

        // Gather context
        String currentUrl   = safeGetUrl(driver);
        String pageTitle    = safeGetTitle(driver);
        String domSnippet   = safeGetSource(driver);
        String errorMessage = result.getFailureReason();
        String exceptionStr = exception != null ? formatException(exception) : "";

        String prompt = buildPrompt(currentUrl, pageTitle, domSnippet, errorMessage, exceptionStr,
                result.getStepsCompleted(), result.getTotalSteps());

        String llmResponse;
        try {
            llmResponse = llmClient.complete(List.of(
                    LLMClient.ChatMessage.system(buildSystemPrompt()),
                    LLMClient.ChatMessage.user(prompt)));
        } catch (Exception e) {
            log.warn("LLM failure analysis request failed: {}", e.getMessage());
            return FailureAnalysis.unavailable(errorMessage, e.getMessage());
        }

        return parseResponse(llmResponse, errorMessage, currentUrl);
    }

    /**
     * Overload that captures context from a raw exception without a PlaybackResult.
     */
    public FailureAnalysis analyze(WebDriver driver, String stepDescription, Throwable exception) {
        log.info("Starting AI failure analysis for: {}", stepDescription);

        String currentUrl   = safeGetUrl(driver);
        String pageTitle    = safeGetTitle(driver);
        String domSnippet   = safeGetSource(driver);
        String exceptionStr = formatException(exception);

        String prompt = buildPrompt(currentUrl, pageTitle, domSnippet,
                stepDescription, exceptionStr, -1, -1);

        String llmResponse;
        try {
            llmResponse = llmClient.complete(List.of(
                    LLMClient.ChatMessage.system(buildSystemPrompt()),
                    LLMClient.ChatMessage.user(prompt)));
        } catch (Exception e) {
            log.warn("LLM failure analysis request failed: {}", e.getMessage());
            return FailureAnalysis.unavailable(stepDescription, e.getMessage());
        }

        return parseResponse(llmResponse, stepDescription, currentUrl);
    }

    // ── Prompt construction ───────────────────────────────────────────────────

    private static String buildSystemPrompt() {
        return """
                You are an expert Selenium test automation engineer analysing a test failure.
                Your job is to provide a concise, accurate root cause analysis and a concrete fix suggestion.

                Respond in exactly this format (no other text):
                ROOT_CAUSE: <1-2 sentence description of why the test failed>
                SUGGESTED_FIX: <concrete, actionable fix — e.g. update locator, add wait, handle new element>
                CATEGORY: <one of: LOCATOR_CHANGED, ELEMENT_NOT_VISIBLE, TIMING, NAVIGATION, DATA_MISMATCH, PAGE_CHANGED, NETWORK_ERROR, OTHER>
                CONFIDENCE: <HIGH | MEDIUM | LOW>
                """;
    }

    private static String buildPrompt(String url, String title, String dom,
                                       String error, String exception,
                                       int stepCompleted, int totalSteps) {
        String stepInfo = stepCompleted >= 0
                ? String.format("Failed at step %d of %d.", stepCompleted, totalSteps)
                : "Failure details:";

        // Trim DOM to avoid exceeding LLM context
        String domTrimmed = dom != null && dom.length() > MAX_DOM_CHARS
                ? dom.substring(0, MAX_DOM_CHARS) + "\n... [TRUNCATED]"
                : (dom != null ? dom : "(unavailable)");

        return String.format("""
                Test Failure Analysis Request

                %s

                Page URL: %s
                Page Title: %s

                Error:
                %s

                Exception:
                %s

                DOM Snapshot (first %d chars):
                %s
                """,
                stepInfo, url, title,
                truncate(error, MAX_ERROR_CHARS),
                truncate(exception, MAX_ERROR_CHARS),
                MAX_DOM_CHARS, domTrimmed);
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private static FailureAnalysis parseResponse(String response, String originalError, String url) {
        String rootCause     = extractField(response, "ROOT_CAUSE:");
        String suggestedFix  = extractField(response, "SUGGESTED_FIX:");
        String category      = extractField(response, "CATEGORY:");
        String confidence    = extractField(response, "CONFIDENCE:");

        if (rootCause == null) rootCause = "LLM response was not in expected format. Raw: " + response;
        if (suggestedFix == null) suggestedFix = "Review the DOM snapshot and update the locator.";

        return new FailureAnalysis(rootCause, suggestedFix, category, confidence,
                originalError, url, response);
    }

    private static String extractField(String response, String prefix) {
        if (response == null) return null;
        for (String line : response.lines().toList()) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    // ── Context capture ───────────────────────────────────────────────────────

    private static String safeGetUrl(WebDriver driver) {
        try { return driver.getCurrentUrl(); } catch (Exception e) { return "(unavailable)"; }
    }

    private static String safeGetTitle(WebDriver driver) {
        try { return driver.getTitle(); } catch (Exception e) { return "(unavailable)"; }
    }

    private static String safeGetSource(WebDriver driver) {
        try { return driver.getPageSource(); } catch (Exception e) { return "(unavailable)"; }
    }

    private static String formatException(Throwable t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        sb.append(": ").append(t.getMessage());
        StackTraceElement[] stack = t.getStackTrace();
        for (int i = 0; i < Math.min(5, stack.length); i++) {
            sb.append("\n  at ").append(stack[i]);
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "(null)";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
