package autoqa.reporting;

import io.qameta.allure.Allure;
import io.qameta.allure.model.Label;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StatusDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * TestNG {@link ITestListener} that bridges test lifecycle events into Allure.
 *
 * <p>On each test completion the listener:
 * <ul>
 *   <li><b>Failure</b> — attaches screenshot PNG, page-source HTML, console log,
 *       and marks the Allure result as {@code BROKEN} with full exception details.</li>
 *   <li><b>Success</b> — marks the result as {@code PASSED}.</li>
 *   <li><b>Skipped</b> — marks the result as {@code SKIPPED}.</li>
 * </ul>
 *
 * <p>Browser environment labels ({@code browser}, {@code browser.version},
 * {@code test.env}) are read from Java system properties and added to every
 * test case via {@code Allure.getLifecycle().updateTestCase(...)}.
 *
 * <p>Evidence files are read from the evidence directory produced by
 * {@link autoqa.player.EvidenceCollector} — the listener does <em>not</em>
 * invoke the collector itself; it only reads artifacts that have already been
 * written to disk during the test run.  All evidence-attachment code is
 * wrapped in {@code try/catch} so a missing evidence directory never aborts
 * the reporting phase.
 */
public class AllureListener implements ITestListener {

    private static final Logger log = LoggerFactory.getLogger(AllureListener.class);

    // System property keys for browser environment labels
    static final String PROP_BROWSER         = "browser";
    static final String PROP_BROWSER_VERSION = "browser.version";
    static final String PROP_TEST_ENV        = "test.env";

    // Evidence directory – must match PlayerConfig#getEvidenceDir() default
    private static final String EVIDENCE_BASE_DIR =
            System.getProperty("player.evidence.dir", "evidence");

    // ── ITestListener callbacks ───────────────────────────────────────────

    @Override
    public void onTestStart(ITestResult result) {
        String name = testName(result);
        log.info("AllureListener: test started — {}", name);
        Allure.step("Test started: " + name);
        addBrowserLabels();
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        String name = testName(result);
        log.info("AllureListener: test passed  — {}", name);

        Allure.getLifecycle().updateTestCase(tc -> {
            tc.setStatus(Status.PASSED);
        });

        log.debug("AllureListener: marked PASSED for {}", name);
    }

    @Override
    public void onTestFailure(ITestResult result) {
        String name = testName(result);
        Throwable cause = result.getThrowable();
        log.warn("AllureListener: test failed  — {} : {}",
                name, cause != null ? cause.getMessage() : "(no exception)");

        // Attach evidence artifacts produced by EvidenceCollector
        attachEvidenceArtifacts(result);

        // Mark result as BROKEN (assertion error → FAILED, but for player failures BROKEN is appropriate)
        Allure.getLifecycle().updateTestCase(tc -> {
            tc.setStatus(Status.BROKEN);
            if (cause != null) {
                tc.setStatusDetails(new StatusDetails()
                        .setMessage(cause.getMessage())
                        .setTrace(stackTraceOf(cause)));
            }
        });

        log.debug("AllureListener: marked BROKEN for {}", name);
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        String name = testName(result);
        log.info("AllureListener: test skipped — {}", name);

        Throwable cause = result.getThrowable();
        Allure.getLifecycle().updateTestCase(tc -> {
            tc.setStatus(Status.SKIPPED);
            if (cause != null) {
                tc.setStatusDetails(new StatusDetails()
                        .setMessage(cause.getMessage())
                        .setTrace(stackTraceOf(cause)));
            }
        });

        log.debug("AllureListener: marked SKIPPED for {}", name);
    }

    @Override
    public void onTestFailedWithTimeout(ITestResult result) {
        String name = testName(result);
        log.warn("AllureListener: test timed out — {}", name);

        // Attach whatever evidence was captured before the timeout
        attachEvidenceArtifacts(result);

        Throwable cause = result.getThrowable();
        Allure.getLifecycle().updateTestCase(tc -> {
            tc.setStatus(Status.BROKEN);
            if (cause != null) {
                tc.setStatusDetails(new StatusDetails()
                        .setMessage("Test timed out: " + cause.getMessage())
                        .setTrace(stackTraceOf(cause)));
            } else {
                tc.setStatusDetails(new StatusDetails()
                        .setMessage("Test timed out (no exception detail available)"));
            }
        });

        log.debug("AllureListener: marked BROKEN (timeout) for {}", name);
    }

    // ── Evidence attachment ───────────────────────────────────────────────

    /**
     * Attempts to read evidence artifacts written by
     * {@link autoqa.player.EvidenceCollector} and attach them to the current
     * Allure test case.
     *
     * <p>The evidence directory path is derived from test attributes stored on
     * the {@link ITestResult} under the key {@code evidenceDir}.  If the
     * attribute is absent the listener falls back to scanning the configured
     * evidence base directory for the most recently modified sub-directory
     * matching the test name.  All failures are logged as warnings and do not
     * propagate.
     */
    private void attachEvidenceArtifacts(ITestResult result) {
        try {
            Path evidenceDir = resolveEvidenceDir(result);
            if (evidenceDir == null || !Files.isDirectory(evidenceDir)) {
                log.debug("AllureListener: no evidence directory found for {}", testName(result));
                return;
            }

            attachScreenshot(evidenceDir);
            attachPageSource(evidenceDir);
            attachConsoleLog(evidenceDir);

        } catch (Exception e) {
            log.warn("AllureListener: failed to attach evidence for {}: {}",
                    testName(result), e.getMessage());
        }
    }

    /**
     * Resolves the evidence directory for this test result.
     *
     * <p>Checks ITestResult attribute {@code evidenceDir} first, then falls back
     * to {@code <evidenceBaseDir>/<sanitisedTestName>/0/}.
     */
    private Path resolveEvidenceDir(ITestResult result) {
        Object attr = result.getAttribute("evidenceDir");
        if (attr instanceof Path) {
            return (Path) attr;
        }
        if (attr instanceof String s && !s.isBlank()) {
            return Paths.get(s);
        }

        // Fallback: use test class + method name as session ID sub-directory
        String sanitised = (result.getTestClass().getName() + "_" + result.getName())
                .replaceAll("[^a-zA-Z0-9_\\-]", "_");
        Path candidate = Paths.get(EVIDENCE_BASE_DIR, sanitised, "0");
        log.debug("AllureListener: using fallback evidence path {}", candidate);
        return candidate;
    }

    private void attachScreenshot(Path dir) {
        try {
            Path file = dir.resolve("screenshot.png");
            if (!Files.exists(file)) return;
            byte[] bytes = Files.readAllBytes(file);
            Allure.addAttachment("Screenshot", "image/png",
                    new ByteArrayInputStream(bytes), "png");
            log.debug("AllureListener: attached screenshot ({} bytes)", bytes.length);
        } catch (Exception e) {
            log.warn("AllureListener: could not attach screenshot: {}", e.getMessage());
        }
    }

    private void attachPageSource(Path dir) {
        try {
            Path file = dir.resolve("page-source.html");
            if (!Files.exists(file)) return;
            String html = Files.readString(file, StandardCharsets.UTF_8);
            Allure.addAttachment("Page Source", "text/html",
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), "html");
            log.debug("AllureListener: attached page source ({} chars)", html.length());
        } catch (Exception e) {
            log.warn("AllureListener: could not attach page source: {}", e.getMessage());
        }
    }

    private void attachConsoleLog(Path dir) {
        try {
            Path file = dir.resolve("console.log");
            if (!Files.exists(file)) return;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Allure.addAttachment("Console Log", "text/plain",
                    new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "log");
            log.debug("AllureListener: attached console log ({} chars)", text.length());
        } catch (Exception e) {
            log.warn("AllureListener: could not attach console log: {}", e.getMessage());
        }
    }

    // ── Browser environment labels ────────────────────────────────────────

    /**
     * Reads browser / environment system properties and adds them as Allure labels
     * on the current test case.
     */
    private void addBrowserLabels() {
        String browser        = System.getProperty(PROP_BROWSER,         "");
        String browserVersion = System.getProperty(PROP_BROWSER_VERSION, "");
        String testEnv        = System.getProperty(PROP_TEST_ENV,        "");

        Allure.getLifecycle().updateTestCase(tc -> {
            if (!browser.isBlank()) {
                tc.getLabels().add(new Label().setName("browser").setValue(browser));
            }
            if (!browserVersion.isBlank()) {
                tc.getLabels().add(new Label().setName("browserVersion").setValue(browserVersion));
            }
            if (!testEnv.isBlank()) {
                tc.getLabels().add(new Label().setName("environment").setValue(testEnv));
            }
        });

        log.debug("AllureListener: browser labels set — browser={} version={} env={}",
                browser, browserVersion, testEnv);
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    private static String testName(ITestResult result) {
        return result.getTestClass().getName() + "#" + result.getName();
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
