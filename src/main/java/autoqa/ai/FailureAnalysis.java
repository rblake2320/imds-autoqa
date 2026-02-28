package autoqa.ai;

/**
 * Structured result of an AI-powered test failure root cause analysis.
 *
 * @see FailureAnalyzer
 */
public class FailureAnalysis {

    private final String  rootCause;
    private final String  suggestedFix;
    private final String  category;       // LOCATOR_CHANGED, TIMING, etc.
    private final String  confidence;     // HIGH, MEDIUM, LOW
    private final String  originalError;
    private final String  pageUrl;
    private final String  rawLlmResponse;
    private final boolean available;

    public FailureAnalysis(String rootCause, String suggestedFix, String category,
                           String confidence, String originalError,
                           String pageUrl, String rawLlmResponse) {
        this.rootCause      = rootCause;
        this.suggestedFix   = suggestedFix;
        this.category       = category;
        this.confidence     = confidence;
        this.originalError  = originalError;
        this.pageUrl        = pageUrl;
        this.rawLlmResponse = rawLlmResponse;
        this.available      = true;
    }

    private FailureAnalysis(String originalError, String unavailableReason) {
        this.rootCause      = "AI analysis unavailable: " + unavailableReason;
        this.suggestedFix   = "Review the test failure manually.";
        this.category       = "OTHER";
        this.confidence     = "LOW";
        this.originalError  = originalError;
        this.pageUrl        = null;
        this.rawLlmResponse = null;
        this.available      = false;
    }

    /** Returns an analysis indicating the LLM was not reachable. */
    public static FailureAnalysis unavailable(String originalError, String reason) {
        return new FailureAnalysis(originalError, reason);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String  getRootCause()      { return rootCause; }
    public String  getSuggestedFix()   { return suggestedFix; }
    public String  getCategory()       { return category; }
    public String  getConfidence()     { return confidence; }
    public String  getOriginalError()  { return originalError; }
    public String  getPageUrl()        { return pageUrl; }
    public String  getRawLlmResponse() { return rawLlmResponse; }
    public boolean isAvailable()       { return available; }

    /** Returns a formatted multi-line report suitable for logging / Allure attachment. */
    public String report() {
        return String.format(
                """
                ═══════════════════════════════════════════════
                AI FAILURE ANALYSIS
                ═══════════════════════════════════════════════
                Original Error : %s
                Page URL       : %s
                Category       : %s
                Confidence     : %s

                Root Cause:
                  %s

                Suggested Fix:
                  %s
                ═══════════════════════════════════════════════
                """,
                originalError, pageUrl, category, confidence, rootCause, suggestedFix);
    }

    @Override
    public String toString() {
        return String.format("FailureAnalysis{category='%s', confidence='%s', available=%s}",
                category, confidence, available);
    }
}
