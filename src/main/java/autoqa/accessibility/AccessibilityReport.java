package autoqa.accessibility;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The result of an axe-core accessibility scan.
 *
 * <p>Equivalent to UFT One's Accessibility checkpoint but dramatically more
 * powerful — covers the full WCAG 2.0/2.1/2.2 rule set (100+ rules) via the
 * industry-standard axe-core engine used by Deque, Google, and Microsoft.
 */
public class AccessibilityReport {

    private final String                     pageUrl;
    private final String                     pageTitle;
    private final List<AccessibilityViolation> violations;
    private final int                        passCount;
    private final int                        incompleteCount;

    public AccessibilityReport(String pageUrl, String pageTitle,
                               List<AccessibilityViolation> violations,
                               int passCount, int incompleteCount) {
        this.pageUrl         = pageUrl;
        this.pageTitle       = pageTitle;
        this.violations      = violations != null ? List.copyOf(violations) : List.of();
        this.passCount       = passCount;
        this.incompleteCount = incompleteCount;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String                       getPageUrl()         { return pageUrl; }
    public String                       getPageTitle()       { return pageTitle; }
    public List<AccessibilityViolation> getViolations()      { return violations; }
    public int                          getViolationCount()  { return violations.size(); }
    public int                          getPassCount()       { return passCount; }
    public int                          getIncompleteCount() { return incompleteCount; }

    /** True when there are no violations of any severity. */
    public boolean isPassed() { return violations.isEmpty(); }

    /** Violations at critical or serious impact level (WCAG A/AA failures). */
    public List<AccessibilityViolation> getCriticalViolations() {
        return violations.stream().filter(AccessibilityViolation::isSevere).collect(Collectors.toList());
    }

    /** True when there are no critical/serious violations. */
    public boolean isPassedForCritical() { return getCriticalViolations().isEmpty(); }

    // ── Assertions (throw AssertionError so they integrate with any framework) ─

    /**
     * Asserts no violations of any level exist.
     *
     * @throws AssertionError with violation summary if any are found
     */
    public AccessibilityReport assertNone() {
        if (!violations.isEmpty()) {
            throw new AssertionError(buildFailureMessage("Expected 0 violations"));
        }
        return this;
    }

    /**
     * Asserts no critical or serious violations exist (WCAG A/AA level).
     *
     * @throws AssertionError if critical/serious violations are found
     */
    public AccessibilityReport assertNoCritical() {
        List<AccessibilityViolation> crit = getCriticalViolations();
        if (!crit.isEmpty()) {
            throw new AssertionError(buildFailureMessage("Expected 0 critical/serious violations",
                    crit));
        }
        return this;
    }

    /**
     * Asserts the violation count does not exceed {@code maxAllowed}.
     *
     * @throws AssertionError if violation count exceeds the limit
     */
    public AccessibilityReport assertMaxViolations(int maxAllowed) {
        if (violations.size() > maxAllowed) {
            throw new AssertionError(buildFailureMessage(
                    "Expected ≤ " + maxAllowed + " violations but found " + violations.size()));
        }
        return this;
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

    /** Returns a human-readable summary string. */
    public String summary() {
        return String.format(
                "Accessibility scan — %s%n  URL: %s%n  Violations: %d | Passes: %d | Incomplete: %d%s",
                violations.isEmpty() ? "PASS ✓" : "FAIL ✗",
                pageUrl,
                violations.size(), passCount, incompleteCount,
                violations.isEmpty() ? "" : "\n" + violationList(violations));
    }

    private String buildFailureMessage(String header) {
        return buildFailureMessage(header, violations);
    }

    private String buildFailureMessage(String header, List<AccessibilityViolation> list) {
        StringBuilder sb = new StringBuilder(header).append("\nPage: ").append(pageUrl).append('\n');
        list.forEach(v -> sb.append("  ").append(v).append('\n'));
        return sb.toString();
    }

    private static String violationList(List<AccessibilityViolation> list) {
        return list.stream()
                .map(v -> "  • " + v)
                .collect(Collectors.joining("\n"));
    }

    @Override
    public String toString() {
        return String.format("AccessibilityReport{violations=%d, passes=%d, url='%s'}",
                violations.size(), passCount, pageUrl);
    }
}
