package autoqa.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ISuiteResult;
import org.testng.ITestResult;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * TestNG {@link ISuiteListener} that orchestrates post-suite reporting activities:
 * <ol>
 *   <li>Generates the Allure HTML report by invoking the {@code allure} CLI tool.</li>
 *   <li>Creates Jira bugs for every failed test (when Jira is configured).</li>
 *   <li>Publishes a summary HTML table to Confluence (when Confluence is configured).</li>
 * </ol>
 *
 * <p>Every external integration is wrapped in its own {@code try/catch} block so
 * one failure never prevents the others from running.
 *
 * <p>Jira and Confluence credentials are read from {@code config.properties} via
 * {@link JiraClient#fromConfig()} and {@link ConfluenceClient#fromConfig()}.
 * If any required keys are blank the corresponding integration is silently skipped.
 */
public class ReportOrchestrator implements ISuiteListener {

    private static final Logger log = LoggerFactory.getLogger(ReportOrchestrator.class);

    /** Path to the Allure results directory (set by Surefire via system property). */
    private static final String ALLURE_RESULTS_DIR =
            System.getProperty("allure.results.directory", "target/allure-results");

    /** Output directory for the generated Allure report. */
    private static final String ALLURE_REPORT_DIR = "target/allure-report";

    /** Wall-clock time when the suite started. */
    private Instant suiteStart;

    // ── ISuiteListener callbacks ──────────────────────────────────────────

    @Override
    public void onStart(ISuite suite) {
        suiteStart = Instant.now();
        log.info("ReportOrchestrator: suite '{}' started", suite.getName());
    }

    @Override
    public void onFinish(ISuite suite) {
        Instant suiteEnd = Instant.now();
        Duration suiteDuration = Duration.between(suiteStart != null ? suiteStart : suiteEnd, suiteEnd);

        // ── Collect statistics ────────────────────────────────────────────
        TestStats stats = collectStats(suite);
        log.info("ReportOrchestrator: suite '{}' finished in {} s — " +
                        "total={} passed={} failed={} skipped={}",
                suite.getName(),
                suiteDuration.toSeconds(),
                stats.total(), stats.passed(), stats.failed(), stats.skipped());

        // ── Generate Allure report ────────────────────────────────────────
        try {
            generateAllureReport();
        } catch (Exception e) {
            log.error("ReportOrchestrator: Allure report generation failed: {}", e.getMessage(), e);
        }

        // ── Create Jira bugs ──────────────────────────────────────────────
        try {
            createJiraBugs(suite);
        } catch (Exception e) {
            log.error("ReportOrchestrator: Jira bug creation failed: {}", e.getMessage(), e);
        }

        // ── Publish Confluence page ───────────────────────────────────────
        try {
            publishConfluencePage(suite);
        } catch (Exception e) {
            log.error("ReportOrchestrator: Confluence publish failed: {}", e.getMessage(), e);
        }
    }

    // ── Allure report generation ──────────────────────────────────────────

    /**
     * Runs {@code allure generate <results-dir> -o <report-dir> --clean} via
     * {@link ProcessBuilder}.  If the {@code allure} executable is not found on
     * PATH the method logs a manual instruction and returns gracefully.
     */
    void generateAllureReport() {
        log.info("ReportOrchestrator: running 'allure generate {} -o {} --clean'",
                ALLURE_RESULTS_DIR, ALLURE_REPORT_DIR);

        ProcessBuilder pb = new ProcessBuilder(
                "allure", "generate", ALLURE_RESULTS_DIR,
                "-o", ALLURE_REPORT_DIR, "--clean");
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            // allure CLI is not on PATH — provide manual instruction
            log.info("ReportOrchestrator: 'allure' CLI not found on PATH. " +
                    "Run 'allure serve {}' manually to view the report.", ALLURE_RESULTS_DIR);
            return;
        }

        try {
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("ReportOrchestrator: Allure report generated at {}", ALLURE_REPORT_DIR);
            } else {
                String output = new String(process.getInputStream().readAllBytes());
                log.warn("ReportOrchestrator: 'allure generate' exited with code {}. Output: {}",
                        exitCode, output);
                log.info("ReportOrchestrator: Run 'allure serve {}' manually to view the report.",
                        ALLURE_RESULTS_DIR);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("ReportOrchestrator: interrupted while waiting for 'allure generate'");
        } catch (IOException e) {
            log.warn("ReportOrchestrator: could not read 'allure generate' output: {}", e.getMessage());
        }
    }

    // ── Jira bug creation ─────────────────────────────────────────────────

    /**
     * Creates a Jira bug for each failed test result in the suite.
     * Silently skips when Jira is not configured.
     */
    void createJiraBugs(ISuite suite) {
        JiraClient jira;
        try {
            jira = JiraClient.fromConfig();
        } catch (Exception e) {
            log.debug("ReportOrchestrator: could not initialise JiraClient — skipping Jira bugs: {}",
                    e.getMessage());
            return;
        }

        if (!jira.isConfigured()) {
            log.debug("ReportOrchestrator: Jira not configured — skipping bug creation");
            return;
        }

        List<ITestResult> failures = collectFailures(suite);
        if (failures.isEmpty()) {
            log.info("ReportOrchestrator: no failed tests — no Jira bugs to create");
            return;
        }

        log.info("ReportOrchestrator: creating {} Jira bug(s) for failed tests", failures.size());

        for (ITestResult failed : failures) {
            try {
                String summary = buildJiraSummary(failed);
                String description = buildJiraDescription(failed);
                String issueKey = jira.createBug(summary, description, null);
                if (issueKey != null) {
                    log.info("ReportOrchestrator: Jira bug {} created for test '{}'",
                            issueKey, testName(failed));
                }
            } catch (Exception e) {
                log.error("ReportOrchestrator: failed to create Jira bug for test '{}': {}",
                        testName(failed), e.getMessage(), e);
            }
        }
    }

    // ── Confluence publishing ─────────────────────────────────────────────

    /**
     * Builds a simple HTML summary table of all test results and publishes it
     * to Confluence.  Silently skips when Confluence is not configured.
     */
    void publishConfluencePage(ISuite suite) {
        ConfluenceClient confluence;
        try {
            confluence = ConfluenceClient.fromConfig();
        } catch (Exception e) {
            log.debug("ReportOrchestrator: could not initialise ConfluenceClient — skipping: {}",
                    e.getMessage());
            return;
        }

        if (!confluence.isConfigured()) {
            log.debug("ReportOrchestrator: Confluence not configured — skipping page publish");
            return;
        }

        String pageTitle = "AutoQA Test Report — " + suite.getName();
        String htmlContent = buildConfluenceHtml(suite);

        log.info("ReportOrchestrator: publishing Confluence page '{}'", pageTitle);
        try {
            confluence.createOrUpdatePage(pageTitle, htmlContent, null, null);
            log.info("ReportOrchestrator: Confluence page '{}' published successfully", pageTitle);
        } catch (Exception e) {
            log.error("ReportOrchestrator: failed to publish Confluence page '{}': {}",
                    pageTitle, e.getMessage(), e);
        }
    }

    // ── Statistics ────────────────────────────────────────────────────────

    /**
     * Collects total / passed / failed / skipped counts across all suite results.
     */
    private TestStats collectStats(ISuite suite) {
        int total = 0, passed = 0, failed = 0, skipped = 0;

        for (ISuiteResult suiteResult : suite.getResults().values()) {
            Collection<ITestResult> allPassed  = suiteResult.getTestContext().getPassedTests().getAllResults();
            Collection<ITestResult> allFailed  = suiteResult.getTestContext().getFailedTests().getAllResults();
            Collection<ITestResult> allSkipped = suiteResult.getTestContext().getSkippedTests().getAllResults();

            passed  += allPassed.size();
            failed  += allFailed.size();
            skipped += allSkipped.size();
            total   += allPassed.size() + allFailed.size() + allSkipped.size();
        }

        return new TestStats(total, passed, failed, skipped);
    }

    /** Returns a flat list of all failed {@link ITestResult}s across the suite. */
    private List<ITestResult> collectFailures(ISuite suite) {
        List<ITestResult> failures = new ArrayList<>();
        for (ISuiteResult suiteResult : suite.getResults().values()) {
            failures.addAll(suiteResult.getTestContext().getFailedTests().getAllResults());
        }
        return failures;
    }

    // ── Content builders ──────────────────────────────────────────────────

    private String buildJiraSummary(ITestResult result) {
        return "[AutoQA] Test failed: " + testName(result);
    }

    private String buildJiraDescription(ITestResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("h3. AutoQA Test Failure Report\n\n");
        sb.append("*Test:* ").append(testName(result)).append("\n");
        sb.append("*Status:* FAILED\n");

        Throwable t = result.getThrowable();
        if (t != null) {
            sb.append("\n*Exception:* ").append(t.getClass().getName()).append("\n");
            sb.append("*Message:* ").append(t.getMessage()).append("\n");
            sb.append("\n{code:java}\n");
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            sb.append(sw);
            sb.append("{code}\n");
        }

        return sb.toString();
    }

    /**
     * Builds a Confluence Storage Format HTML table summarising all test results.
     */
    private String buildConfluenceHtml(ISuite suite) {
        StringBuilder html = new StringBuilder();
        html.append("<h2>AutoQA Test Execution Summary</h2>");
        html.append("<p>Suite: <strong>").append(escapeHtml(suite.getName())).append("</strong></p>");
        html.append("<table>");
        html.append("<tbody>");
        html.append("<tr>");
        html.append("<th>Test Name</th>");
        html.append("<th>Status</th>");
        html.append("<th>Duration (ms)</th>");
        html.append("<th>Failure Message</th>");
        html.append("</tr>");

        for (Map.Entry<String, ISuiteResult> entry : suite.getResults().entrySet()) {
            appendRows(html, entry.getValue());
        }

        html.append("</tbody>");
        html.append("</table>");
        return html.toString();
    }

    private void appendRows(StringBuilder html, ISuiteResult suiteResult) {
        appendResultRows(html, suiteResult.getTestContext().getPassedTests().getAllResults(),  "PASSED",  "#dff0d8");
        appendResultRows(html, suiteResult.getTestContext().getFailedTests().getAllResults(),  "FAILED",  "#f2dede");
        appendResultRows(html, suiteResult.getTestContext().getSkippedTests().getAllResults(), "SKIPPED", "#fcf8e3");
    }

    private void appendResultRows(StringBuilder html, Collection<ITestResult> results,
                                  String statusLabel, String bgColor) {
        for (ITestResult r : results) {
            long durationMs = r.getEndMillis() - r.getStartMillis();
            String failureMsg = "";
            if (r.getThrowable() != null) {
                failureMsg = escapeHtml(String.valueOf(r.getThrowable().getMessage()));
            }

            html.append("<tr style=\"background-color:").append(bgColor).append("\">");
            html.append("<td>").append(escapeHtml(testName(r))).append("</td>");
            html.append("<td><strong>").append(statusLabel).append("</strong></td>");
            html.append("<td>").append(durationMs).append("</td>");
            html.append("<td>").append(failureMsg).append("</td>");
            html.append("</tr>");
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static String testName(ITestResult result) {
        return result.getTestClass().getName() + "#" + result.getName();
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }

    // ── Internal record ───────────────────────────────────────────────────

    private record TestStats(int total, int passed, int failed, int skipped) {}
}
