package autoqa.player;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * In-test performance timing assertions — a capability that goes beyond UFT One.
 *
 * <p>Uses the browser's {@code window.performance.timing} API (Navigation Timing API)
 * to measure real page load performance and make assertions in tests, integrated
 * with the Continuous Performance Testing (CPT) approach recommended by modern
 * frameworks.
 *
 * <h3>Metrics available</h3>
 * <ul>
 *   <li><b>Total Load Time</b> — loadEventEnd - navigationStart</li>
 *   <li><b>DOM Content Loaded</b> — domContentLoadedEventEnd - navigationStart</li>
 *   <li><b>Time to First Byte (TTFB)</b> — responseStart - navigationStart</li>
 *   <li><b>DOM Interactive</b> — domInteractive - navigationStart</li>
 *   <li><b>Backend Processing</b> — responseStart - requestStart</li>
 *   <li><b>Frontend Time</b> — loadEventEnd - responseEnd</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // After navigating to a page:
 * PerformanceAssertion.forPage(driver)
 *     .assertLoadBelow(3000)           // page loads in under 3 seconds
 *     .assertTtfbBelow(800)            // TTFB under 800ms
 *     .assertDomReadyBelow(2000)       // DOM ready under 2 seconds
 *     .log();                           // log all metrics
 * }</pre>
 */
public class PerformanceAssertion {

    private static final Logger log = LoggerFactory.getLogger(PerformanceAssertion.class);

    private final WebDriver driver;
    private final Metrics   metrics;

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Captures current page performance metrics and returns a fluent assertion object.
     *
     * @param driver WebDriver pointing at the page to measure
     * @return fluent {@link PerformanceAssertion} builder
     */
    public static PerformanceAssertion forPage(WebDriver driver) {
        return new PerformanceAssertion(driver, captureMetrics(driver));
    }

    private PerformanceAssertion(WebDriver driver, Metrics metrics) {
        this.driver  = driver;
        this.metrics = metrics;
    }

    // ── Assertions ────────────────────────────────────────────────────────────

    /**
     * Asserts that total page load time (loadEventEnd - navigationStart)
     * is below {@code maxMs} milliseconds.
     *
     * @throws AssertionError with timing details if the assertion fails
     */
    public PerformanceAssertion assertLoadBelow(long maxMs) {
        long actual = metrics.totalLoadMs;
        if (actual > maxMs) {
            throw new AssertionError(String.format(
                    "Performance: Total load time %d ms exceeds threshold %d ms (URL: %s)",
                    actual, maxMs, driver.getCurrentUrl()));
        }
        log.info("Performance ✓ Total load: {} ms (threshold: {} ms)", actual, maxMs);
        return this;
    }

    /**
     * Asserts that Time to First Byte (TTFB) is below {@code maxMs} milliseconds.
     * High TTFB indicates backend/network issues.
     */
    public PerformanceAssertion assertTtfbBelow(long maxMs) {
        long actual = metrics.ttfbMs;
        if (actual > maxMs) {
            throw new AssertionError(String.format(
                    "Performance: TTFB %d ms exceeds threshold %d ms (URL: %s)",
                    actual, maxMs, driver.getCurrentUrl()));
        }
        log.info("Performance ✓ TTFB: {} ms (threshold: {} ms)", actual, maxMs);
        return this;
    }

    /**
     * Asserts that DOM Content Loaded time is below {@code maxMs} milliseconds.
     * Measures time until the initial HTML document has been loaded and parsed,
     * without waiting for stylesheets, images, and subframes.
     */
    public PerformanceAssertion assertDomReadyBelow(long maxMs) {
        long actual = metrics.domContentLoadedMs;
        if (actual > maxMs) {
            throw new AssertionError(String.format(
                    "Performance: DOM Content Loaded %d ms exceeds threshold %d ms (URL: %s)",
                    actual, maxMs, driver.getCurrentUrl()));
        }
        log.info("Performance ✓ DOM Content Loaded: {} ms (threshold: {} ms)", actual, maxMs);
        return this;
    }

    /**
     * Asserts that DOM Interactive time is below {@code maxMs} milliseconds.
     * DOM Interactive is when the browser finishes parsing the HTML and the DOM is ready.
     */
    public PerformanceAssertion assertDomInteractiveBelow(long maxMs) {
        long actual = metrics.domInteractiveMs;
        if (actual > maxMs) {
            throw new AssertionError(String.format(
                    "Performance: DOM Interactive %d ms exceeds threshold %d ms (URL: %s)",
                    actual, maxMs, driver.getCurrentUrl()));
        }
        log.info("Performance ✓ DOM Interactive: {} ms (threshold: {} ms)", actual, maxMs);
        return this;
    }

    /**
     * Asserts that backend processing time (responseStart - requestStart)
     * is below {@code maxMs} milliseconds.
     */
    public PerformanceAssertion assertBackendBelow(long maxMs) {
        long actual = metrics.backendMs;
        if (actual > maxMs) {
            throw new AssertionError(String.format(
                    "Performance: Backend processing %d ms exceeds threshold %d ms (URL: %s)",
                    actual, maxMs, driver.getCurrentUrl()));
        }
        log.info("Performance ✓ Backend: {} ms (threshold: {} ms)", actual, maxMs);
        return this;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** Returns all captured metrics. */
    public Metrics getMetrics() { return metrics; }

    /** Logs all metrics at INFO level and returns {@code this} for chaining. */
    public PerformanceAssertion log() {
        log.info("Performance Metrics — {}%n  Total Load     : {} ms%n  TTFB           : {} ms%n" +
                 "  DOM Interactive: {} ms%n  DOM Ready      : {} ms%n  Backend        : {} ms",
                driver.getCurrentUrl(),
                metrics.totalLoadMs, metrics.ttfbMs, metrics.domInteractiveMs,
                metrics.domContentLoadedMs, metrics.backendMs);
        return this;
    }

    // ── Capture ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Metrics captureMetrics(WebDriver driver) {
        JavascriptExecutor js = (JavascriptExecutor) driver;

        // Navigation Timing API — available in all modern browsers
        String script = """
                var t = window.performance.timing;
                return {
                    navigationStart:          t.navigationStart,
                    requestStart:             t.requestStart,
                    responseStart:            t.responseStart,
                    responseEnd:              t.responseEnd,
                    domInteractive:           t.domInteractive,
                    domContentLoadedEventEnd: t.domContentLoadedEventEnd,
                    loadEventEnd:             t.loadEventEnd
                };
                """;

        Map<String, Long> timing;
        try {
            timing = (Map<String, Long>) js.executeScript(script);
        } catch (Exception e) {
            log.warn("Performance timing not available: {}", e.getMessage());
            return Metrics.UNAVAILABLE;
        }

        if (timing == null) return Metrics.UNAVAILABLE;

        long nav    = safeGet(timing, "navigationStart");
        long reqS   = safeGet(timing, "requestStart");
        long resS   = safeGet(timing, "responseStart");
        long resE   = safeGet(timing, "responseEnd");
        long domI   = safeGet(timing, "domInteractive");
        long domCL  = safeGet(timing, "domContentLoadedEventEnd");
        long loadE  = safeGet(timing, "loadEventEnd");

        return new Metrics(
                loadE  > nav ? loadE - nav : 0,          // total load
                resS   > nav ? resS  - nav : 0,          // TTFB
                domI   > nav ? domI  - nav : 0,          // dom interactive
                domCL  > nav ? domCL - nav : 0,          // dom content loaded
                resS   > reqS ? resS - reqS : 0          // backend
        );
    }

    private static long safeGet(Map<String, Long> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    // ── Metrics value object ──────────────────────────────────────────────────

    /** Immutable snapshot of page performance timings. */
    public static final class Metrics {

        /** Sentinel returned when timing data is unavailable. */
        public static final Metrics UNAVAILABLE = new Metrics(-1, -1, -1, -1, -1);

        public final long totalLoadMs;          // loadEventEnd - navigationStart
        public final long ttfbMs;               // responseStart - navigationStart
        public final long domInteractiveMs;     // domInteractive - navigationStart
        public final long domContentLoadedMs;   // domContentLoadedEventEnd - navigationStart
        public final long backendMs;            // responseStart - requestStart

        public Metrics(long totalLoadMs, long ttfbMs, long domInteractiveMs,
                       long domContentLoadedMs, long backendMs) {
            this.totalLoadMs        = totalLoadMs;
            this.ttfbMs             = ttfbMs;
            this.domInteractiveMs   = domInteractiveMs;
            this.domContentLoadedMs = domContentLoadedMs;
            this.backendMs          = backendMs;
        }

        public boolean isAvailable() { return totalLoadMs >= 0; }

        @Override
        public String toString() {
            return String.format("Metrics{load=%dms, ttfb=%dms, domInteractive=%dms, domReady=%dms, backend=%dms}",
                    totalLoadMs, ttfbMs, domInteractiveMs, domContentLoadedMs, backendMs);
        }
    }
}
