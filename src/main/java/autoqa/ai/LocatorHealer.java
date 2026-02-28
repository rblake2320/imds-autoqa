package autoqa.ai;

import autoqa.model.ElementInfo;
import autoqa.model.ElementLocator;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Uses the LLM to suggest an alternative locator when the original fails.
 * Also provides a DOM-tree-comparison fallback for offline / LLM-unavailable scenarios.
 *
 * <p>The primary path sends a structured prompt containing the failed element's
 * recorded metadata and a truncated snapshot of the live page HTML. The LLM is
 * expected to return either a bare CSS selector / XPath string, or the sentinel
 * value {@code CANNOT_HEAL} when it cannot make a confident suggestion.
 *
 * <p>The fallback path ({@link #healByDomComparison}) constructs an XPath
 * expression based on the element's visible text content and tag name, requiring
 * no network call.
 */
public class LocatorHealer {

    private static final Logger log = LoggerFactory.getLogger(LocatorHealer.class);

    /** Sentinel returned by the LLM when it cannot suggest a locator. */
    private static final String CANNOT_HEAL_SENTINEL = "CANNOT_HEAL";

    /** Maximum number of text characters to feed into the XPath text-match fallback. */
    private static final int TEXT_MATCH_MAX_CHARS = 30;

    private final LLMClient llm;
    private final int domSnippetChars;

    /**
     * @param llm            configured LLM client
     * @param domSnippetChars maximum characters of page HTML to include in the prompt
     */
    public LocatorHealer(LLMClient llm, int domSnippetChars) {
        this.llm            = llm;
        this.domSnippetChars = domSnippetChars;
    }

    // ── Primary: LLM healing ──────────────────────────────────────────────

    /**
     * Asks the LLM to suggest an alternative locator for the failed element.
     *
     * @param failedElement the {@link ElementInfo} that failed all locator strategies
     * @param pageSource    current page HTML (truncated to {@code domSnippetChars})
     * @param currentUrl    current page URL, included in the prompt for context
     * @return a {@link HealingResult} describing success or failure
     */
    public HealingResult heal(ElementInfo failedElement, String pageSource, String currentUrl) {
        String truncatedDom = pageSource != null && pageSource.length() > domSnippetChars
                ? pageSource.substring(0, domSnippetChars) + "\n... [TRUNCATED]"
                : (pageSource != null ? pageSource : "");

        String systemPrompt = """
                You are a Selenium locator healing expert. Given a failed element description
                and the current page HTML, suggest an alternative CSS selector or XPath that
                uniquely identifies the element.

                Rules:
                - Return ONLY the locator string, nothing else
                - Prefer CSS selectors over XPath when possible
                - The locator must be valid CSS or XPath syntax
                - Do not include "By.cssSelector(" or similar wrappers
                - If you cannot determine a valid locator, return exactly: CANNOT_HEAL
                """;

        String attributesStr = failedElement.getAttributes() != null
                ? failedElement.getAttributes().toString()
                : "{}";

        String userPrompt = """
                Failed element details:
                - Tag: %s
                - ID: %s
                - Name: %s
                - CSS: %s
                - XPath: %s
                - Text: %s
                - All attributes: %s

                Current URL: %s

                Page HTML (truncated):
                %s

                Suggest an alternative CSS selector or XPath to find this element:
                """.formatted(
                        failedElement.getTagName(),
                        failedElement.getId(),
                        failedElement.getName(),
                        failedElement.getCss(),
                        failedElement.getXpath(),
                        failedElement.getText(),
                        attributesStr,
                        currentUrl,
                        truncatedDom);

        log.debug("Sending healing prompt for element: {}", failedElement);

        try {
            String response = llm.complete(List.of(
                    LLMClient.ChatMessage.system(systemPrompt),
                    LLMClient.ChatMessage.user(userPrompt)
            )).trim();

            log.info("Healer response: {}", response);

            if (CANNOT_HEAL_SENTINEL.equals(response) || response.isBlank()) {
                return HealingResult.failed("LLM returned CANNOT_HEAL");
            }

            ElementLocator.Strategy strategy = determineStrategy(response);
            return HealingResult.success(response, strategy);

        } catch (IOException e) {
            log.warn("LLM healing request failed: {}", e.getMessage());
            return HealingResult.failed("LLM error: " + e.getMessage());
        }
    }

    // ── Fallback: DOM comparison ──────────────────────────────────────────

    /**
     * Fallback healing strategy that does not require an LLM call.
     *
     * <p>Constructs an XPath expression matching the element's tag name and a
     * prefix of its visible text content. Returns a failure result if the element
     * has no usable text.
     *
     * @param failedElement the element to locate
     * @param driver        active WebDriver session (accepted for API symmetry but
     *                      not used in the current text-based implementation)
     * @return a {@link HealingResult} with an XPath locator, or a failed result
     */
    public HealingResult healByDomComparison(ElementInfo failedElement, WebDriver driver) {
        String text = failedElement.getText();
        if (text != null && !text.isBlank()) {
            String tag = failedElement.getTagName() != null ? failedElement.getTagName() : "*";
            String textPrefix = text.substring(0, Math.min(TEXT_MATCH_MAX_CHARS, text.length()));
            // Escape any single-quotes in the text to keep XPath valid
            textPrefix = textPrefix.replace("'", "\\'");
            String textXpath = "//" + tag
                    + "[contains(normalize-space(text()),'" + textPrefix + "')]";
            log.debug("DOM comparison fallback generated XPath: {}", textXpath);
            return HealingResult.success(textXpath, ElementLocator.Strategy.XPATH);
        }
        return HealingResult.failed("No text available for DOM comparison fallback");
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Heuristically determines whether the LLM response is XPath or CSS.
     *
     * @param locator the raw locator string returned by the LLM
     * @return {@link ElementLocator.Strategy#XPATH} if the string starts with
     *         {@code //} or {@code (//}; {@link ElementLocator.Strategy#CSS} otherwise
     */
    private ElementLocator.Strategy determineStrategy(String locator) {
        return (locator.startsWith("//") || locator.startsWith("(//"))
                ? ElementLocator.Strategy.XPATH
                : ElementLocator.Strategy.CSS;
    }

    // ── Result record ─────────────────────────────────────────────────────

    /**
     * Immutable result of a single healing attempt.
     *
     * @param healed        {@code true} when a candidate locator was produced
     * @param locatorValue  the suggested locator string, or {@code null} on failure
     * @param strategy      the detected strategy (CSS or XPATH), or {@code null} on failure
     * @param failureReason human-readable explanation when {@code healed} is {@code false}
     */
    public record HealingResult(
            boolean healed,
            String locatorValue,
            ElementLocator.Strategy strategy,
            String failureReason) {

        /**
         * Creates a successful healing result.
         *
         * @param locator  the suggested locator value
         * @param strategy the detected strategy
         */
        public static HealingResult success(String locator, ElementLocator.Strategy strategy) {
            return new HealingResult(true, locator, strategy, null);
        }

        /**
         * Creates a failed healing result.
         *
         * @param reason human-readable reason for the failure
         */
        public static HealingResult failed(String reason) {
            return new HealingResult(false, null, null, reason);
        }
    }
}
