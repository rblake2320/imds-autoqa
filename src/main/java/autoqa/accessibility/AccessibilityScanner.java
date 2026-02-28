package autoqa.accessibility;

import autoqa.player.AutoQAException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Automated WCAG accessibility scanner — the IMDS AutoQA equivalent of UFT
 * One's Accessibility checkpoint, but powered by the industry-standard
 * <a href="https://github.com/dequelabs/axe-core">axe-core</a> engine.
 *
 * <p>axe-core is used by Deque, Google Lighthouse, Microsoft, and 100+ other
 * tools.  It covers 90+ WCAG 2.0/2.1/2.2 rules (A, AA, AAA) and reports zero
 * false positives by design.
 *
 * <p>This scanner injects the axe-core JS bundle from a bundled resource
 * (or optionally a CDN URL) into the current page, runs the analysis, and
 * parses the structured violation report.
 *
 * <h3>UFT Parity + Beyond</h3>
 * <ul>
 *   <li>UFT One: ~20 WCAG rules checked</li>
 *   <li>IMDS AutoQA (axe-core 4.x): 90+ WCAG rules, zero false positives</li>
 *   <li>Supports include/exclude CSS selector scoping</li>
 *   <li>Returns machine-readable JSON → integrate with Allure/Jira</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * AccessibilityScanner scanner = new AccessibilityScanner(driver);
 * AccessibilityReport report = scanner.scan();
 * report.assertNoCritical();
 * System.out.println(report.summary());
 * }</pre>
 */
public class AccessibilityScanner {

    private static final Logger log = LoggerFactory.getLogger(AccessibilityScanner.class);

    /**
     * Bundled axe-core 4.10 CDN URL.  The scanner injects this script into the
     * page at scan time.  For air-gapped environments, serve axe.min.js from a
     * local web server and override this constant.
     *
     * <p>Note: axe-core is MIT-licensed and may be bundled freely.
     */
    public static final String AXE_CDN_URL =
            "https://cdnjs.cloudflare.com/ajax/libs/axe-core/4.10.0/axe.min.js";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebDriver driver;
    private final String    axeSource;  // JS source or null → use CDN

    // ── Constructors ─────────────────────────────────────────────────────────

    /** Creates a scanner that loads axe-core from the Cloudflare CDN. */
    public AccessibilityScanner(WebDriver driver) {
        this(driver, null);
    }

    /**
     * Creates a scanner with a pre-loaded axe-core JS source string.
     * Use this for air-gapped environments where CDN access is not available.
     *
     * @param driver    WebDriver session
     * @param axeSource full axe-core JS source, or null to use CDN injection
     */
    public AccessibilityScanner(WebDriver driver, String axeSource) {
        this.driver    = driver;
        this.axeSource = axeSource;
    }

    // ── Scanning ──────────────────────────────────────────────────────────────

    /**
     * Scans the entire current page against all default axe-core rules.
     *
     * @return an {@link AccessibilityReport} with all violations and passes
     * @throws AutoQAException if axe-core cannot be injected or executed
     */
    public AccessibilityReport scan() {
        return scan(null, null);
    }

    /**
     * Scans a specific CSS-selector-scoped region of the page.
     *
     * @param include CSS selector to scope the scan (null = entire document)
     * @param exclude CSS selector to exclude from the scan (null = none)
     * @return accessibility report for the scoped region
     */
    public AccessibilityReport scan(String include, String exclude) {
        log.info("Starting axe-core accessibility scan on: {}", driver.getCurrentUrl());

        JavascriptExecutor js = (JavascriptExecutor) driver;

        // 1. Inject axe-core if not already present
        injectAxeCore(js);

        // 2. Build the axe.run() call with optional scope
        String runScript = buildRunScript(include, exclude);

        // 3. Execute and wait for result (axe.run is async → we use synchronous wrapper)
        Object rawResult;
        try {
            rawResult = js.executeAsyncScript(runScript);
        } catch (Exception e) {
            throw new AutoQAException("axe-core scan failed: " + e.getMessage(), e);
        }

        if (rawResult == null) {
            throw new AutoQAException("axe-core returned null — page may have reloaded during scan");
        }

        // 4. Parse the JSON result
        return parseResult(rawResult.toString());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void injectAxeCore(JavascriptExecutor js) {
        // Check if already injected
        Object alreadyLoaded = js.executeScript("return typeof axe !== 'undefined';");
        if (Boolean.TRUE.equals(alreadyLoaded)) {
            log.debug("axe-core already loaded on page");
            return;
        }

        if (axeSource != null) {
            log.debug("Injecting bundled axe-core source");
            js.executeScript(axeSource);
        } else {
            // Load from CDN via dynamic script tag injection
            log.debug("Injecting axe-core from CDN: {}", AXE_CDN_URL);
            String inject = "var s=document.createElement('script');" +
                    "s.src=arguments[0];" +
                    "s.type='text/javascript';" +
                    "document.head.appendChild(s);";
            js.executeScript(inject, AXE_CDN_URL);

            // Wait for axe to be defined (poll up to 10 s)
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 10_000) {
                Object loaded = js.executeScript("return typeof axe !== 'undefined';");
                if (Boolean.TRUE.equals(loaded)) break;
                try { Thread.sleep(200); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Object loaded = js.executeScript("return typeof axe !== 'undefined';");
            if (!Boolean.TRUE.equals(loaded)) {
                throw new AutoQAException("axe-core failed to load from CDN within 10 seconds. "
                        + "For air-gapped environments use AccessibilityScanner(driver, axeSource).");
            }
        }
        log.debug("axe-core loaded successfully");
    }

    private static String buildRunScript(String include, String exclude) {
        StringBuilder sb = new StringBuilder();
        sb.append("var callback = arguments[arguments.length - 1];");

        if (include != null || exclude != null) {
            sb.append("var ctx = {");
            if (include != null) sb.append("include: [['").append(escapeJs(include)).append("']],");
            if (exclude != null) sb.append("exclude: [['").append(escapeJs(exclude)).append("']],");
            sb.append("};");
            sb.append("axe.run(ctx, {}, function(err, results) {");
        } else {
            sb.append("axe.run(function(err, results) {");
        }
        sb.append("  if (err) { callback(JSON.stringify({error: err.message})); return; }");
        sb.append("  callback(JSON.stringify(results));");
        sb.append("});");

        return sb.toString();
    }

    private AccessibilityReport parseResult(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);

            // Check for axe-core error
            if (root.has("error")) {
                throw new AutoQAException("axe-core returned error: " + root.get("error").asText());
            }

            List<AccessibilityViolation> violations = new ArrayList<>();
            JsonNode violationsNode = root.path("violations");
            if (violationsNode.isArray()) {
                for (JsonNode v : violationsNode) {
                    String id          = v.path("id").asText();
                    String description = v.path("description").asText();
                    String impact      = v.path("impact").asText("unknown");
                    String tags        = extractFirstMatchingTag(v.path("tags"));

                    List<String> nodes = new ArrayList<>();
                    JsonNode nodesArr = v.path("nodes");
                    if (nodesArr.isArray()) {
                        for (JsonNode n : nodesArr) {
                            // target is an array of CSS selectors
                            JsonNode target = n.path("target");
                            if (target.isArray() && !target.isEmpty()) {
                                nodes.add(target.get(0).asText());
                            }
                        }
                    }
                    violations.add(new AccessibilityViolation(id, description, impact, tags, nodes));
                }
            }

            int passCount       = root.path("passes").size();
            int incompleteCount = root.path("incomplete").size();
            String url          = root.path("url").asText(driver.getCurrentUrl());
            String title        = driver.getTitle();

            log.info("Accessibility scan complete: {} violations, {} passes, {} incomplete",
                    violations.size(), passCount, incompleteCount);

            return new AccessibilityReport(url, title, violations, passCount, incompleteCount);

        } catch (AutoQAException e) {
            throw e;
        } catch (Exception e) {
            throw new AutoQAException("Failed to parse axe-core result: " + e.getMessage(), e);
        }
    }

    private static String extractFirstMatchingTag(JsonNode tagsNode) {
        if (!tagsNode.isArray()) return "";
        for (JsonNode tag : tagsNode) {
            String t = tag.asText();
            if (t.startsWith("wcag")) return t;
        }
        return tagsNode.isEmpty() ? "" : tagsNode.get(0).asText();
    }

    private static String escapeJs(String s) {
        return s.replace("'", "\\'").replace("\"", "\\\"");
    }
}
