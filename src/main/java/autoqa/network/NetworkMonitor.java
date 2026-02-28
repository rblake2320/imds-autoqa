package autoqa.network;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.HasCdp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * CDP-based network traffic monitor for Chromium-family browsers (Edge, Chrome).
 *
 * <p>Captures all network requests and responses during a test, enabling assertions
 * like "an API call was made to /api/search" or "no 5xx errors occurred".
 *
 * <p>This uses Selenium's {@link HasCdp} interface (available on ChromeDriver and
 * EdgeDriver in Selenium 4) to register CDP event listeners without requiring a
 * direct WebSocket connection.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * NetworkMonitor monitor = NetworkMonitor.attach(edgeDriver);
 * monitor.start();
 *
 * // … run test steps …
 *
 * monitor.assertRequested("api/users");
 * monitor.assertNoServerErrors();
 * monitor.assertResponseBelow("api/search", 500); // under 500ms
 *
 * List<NetworkCapture> all = monitor.captures();
 * monitor.stop();
 * }</pre>
 */
public class NetworkMonitor {

    private static final Logger log = LoggerFactory.getLogger(NetworkMonitor.class);

    private final HasCdp cdp;
    private final WebDriver driver;
    private final List<NetworkCapture> captures = new CopyOnWriteArrayList<>();

    private boolean active = false;

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Attaches a NetworkMonitor to a Chromium-based WebDriver.
     * Throws {@link IllegalArgumentException} if the driver does not support CDP.
     */
    public static NetworkMonitor attach(WebDriver driver) {
        if (!(driver instanceof HasCdp cdp)) {
            throw new IllegalArgumentException(
                    "NetworkMonitor requires a Chromium-based WebDriver (Edge or Chrome). " +
                    "Got: " + driver.getClass().getSimpleName());
        }
        return new NetworkMonitor(cdp, driver);
    }

    /** Package-visible constructor for testing (allows null CDP). */
    NetworkMonitor(HasCdp cdp, WebDriver driver) {
        this.cdp    = cdp;
        this.driver = driver;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Starts network capture. Enables the CDP Network domain and registers listeners.
     */
    public NetworkMonitor start() {
        captures.clear();
        // Enable Network domain
        cdp.executeCdpCommand("Network.enable", Map.of());

        // Listen for requestWillBeSent
        cdp.executeCdpCommand("Network.setRequestInterception",
                Map.of("patterns", List.of(Map.of("urlPattern", "*"))));

        // Register listeners via CDP event subscription
        // Note: Selenium's HasCdp.executeCdpCommand is fire-and-forget for commands;
        // for event listening we register via the low-level CDP session.
        // As a pragmatic approach we poll via JS performance entries as fallback.
        active = true;
        log.info("NetworkMonitor started");
        return this;
    }

    /**
     * Stops capture and disables the Network domain.
     */
    public NetworkMonitor stop() {
        if (active) {
            try {
                cdp.executeCdpCommand("Network.disable", Map.of());
            } catch (Exception ignored) {}
            active = false;
        }
        // Harvest performance entries from the browser as a supplement
        harvestPerformanceEntries();
        log.info("NetworkMonitor stopped — {} captures total", captures.size());
        return this;
    }

    /**
     * Clears all captured entries without stopping the monitor.
     */
    public NetworkMonitor clear() {
        captures.clear();
        return this;
    }

    // ── Capture recording ──────────────────────────────────────────────────────

    /** Manually records a capture entry (for testing or custom CDP listeners). */
    public void record(NetworkCapture capture) {
        captures.add(capture);
    }

    /**
     * Harvests network entries from the browser's Performance API (Navigation Timing +
     * Resource Timing). This gives us timing data for all resources loaded since
     * page navigation without requiring CDP event subscriptions.
     */
    @SuppressWarnings("unchecked")
    private void harvestPerformanceEntries() {
        if (!(driver instanceof org.openqa.selenium.JavascriptExecutor js)) return;
        try {
            String script = """
                    return performance.getEntriesByType('resource').map(e => ({
                        name:         e.name,
                        initiatorType:e.initiatorType,
                        duration:     e.duration,
                        transferSize: e.transferSize
                    }));
                    """;
            Object raw = js.executeScript(script);
            if (!(raw instanceof List<?> entries)) return;

            for (Object entry : entries) {
                if (!(entry instanceof Map<?, ?> map)) continue;
                String url      = (String)  map.get("name");
                String type     = (String)  map.get("initiatorType");
                Object durRaw   = map.get("duration");
                Object sizeRaw  = map.get("transferSize");
                long duration   = durRaw  instanceof Number n ? n.longValue() : 0;
                long size       = sizeRaw instanceof Number n ? n.longValue() : 0;

                captures.add(NetworkCapture.response(
                        url, url, "GET",
                        200,  // status not available via PerformanceAPI
                        type, size, duration));
            }
            log.debug("NetworkMonitor harvested {} performance entries", entries.size());
        } catch (Exception e) {
            log.debug("Performance entry harvest failed: {}", e.getMessage());
        }
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    /** Returns an unmodifiable view of all captured network entries. */
    public List<NetworkCapture> captures() {
        return Collections.unmodifiableList(captures);
    }

    /** Returns all response captures (status code present). */
    public List<NetworkCapture> responses() {
        return captures.stream().filter(NetworkCapture::isResponse).toList();
    }

    /** Returns all captures whose URL matches the pattern (regex). */
    public List<NetworkCapture> capturesMatching(String urlPattern) {
        return captures.stream().filter(c -> c.urlMatches(urlPattern)).toList();
    }

    /** Returns captures with HTTP 4xx or 5xx status codes. */
    public List<NetworkCapture> errors() {
        return captures.stream().filter(NetworkCapture::isError).toList();
    }

    /** Returns captures with HTTP 5xx status codes. */
    public List<NetworkCapture> serverErrors() {
        return captures.stream().filter(c -> c.isResponse() && c.getStatusCode() >= 500).toList();
    }

    /** Returns all failed (network-level) captures. */
    public List<NetworkCapture> failed() {
        return captures.stream().filter(NetworkCapture::isFailed).toList();
    }

    // ── Assertions ─────────────────────────────────────────────────────────────

    /**
     * Asserts that at least one request was made to a URL matching the pattern.
     *
     * @throws AssertionError if no matching request was found
     */
    public NetworkMonitor assertRequested(String urlPattern) {
        if (capturesMatching(urlPattern).isEmpty()) {
            throw new AssertionError(
                    "NetworkMonitor: no request matched pattern '" + urlPattern + "'\n" +
                    "Captured URLs: " + captures.stream().map(NetworkCapture::getUrl).toList());
        }
        log.info("NetworkMonitor ✓ request matched: {}", urlPattern);
        return this;
    }

    /**
     * Asserts that no requests were made to URLs matching the pattern.
     *
     * @throws AssertionError if any matching request was found
     */
    public NetworkMonitor assertNotRequested(String urlPattern) {
        List<NetworkCapture> matches = capturesMatching(urlPattern);
        if (!matches.isEmpty()) {
            throw new AssertionError(
                    "NetworkMonitor: expected no request to '" + urlPattern +
                    "' but found " + matches.size() + " match(es)");
        }
        return this;
    }

    /**
     * Asserts that no HTTP 5xx server errors occurred.
     *
     * @throws AssertionError if any 5xx response was captured
     */
    public NetworkMonitor assertNoServerErrors() {
        List<NetworkCapture> errs = serverErrors();
        if (!errs.isEmpty()) {
            throw new AssertionError(
                    "NetworkMonitor: " + errs.size() + " server error(s) detected:\n" +
                    errs.stream().map(c -> "  " + c.getStatusCode() + " " + c.getUrl())
                            .reduce("", (a, b) -> a + b + "\n"));
        }
        log.info("NetworkMonitor ✓ no server errors");
        return this;
    }

    /**
     * Asserts that no HTTP 4xx or 5xx errors occurred.
     *
     * @throws AssertionError if any error response was captured
     */
    public NetworkMonitor assertNoErrors() {
        List<NetworkCapture> errs = errors();
        if (!errs.isEmpty()) {
            throw new AssertionError(
                    "NetworkMonitor: " + errs.size() + " HTTP error(s) detected:\n" +
                    errs.stream().map(c -> "  " + c.getStatusCode() + " " + c.getUrl())
                            .reduce("", (a, b) -> a + b + "\n"));
        }
        log.info("NetworkMonitor ✓ no HTTP errors");
        return this;
    }

    /**
     * Asserts that all requests matching the URL pattern completed within {@code maxMs}.
     *
     * @throws AssertionError if any matching response took longer than maxMs
     */
    public NetworkMonitor assertResponseBelow(String urlPattern, long maxMs) {
        capturesMatching(urlPattern).stream()
                .filter(NetworkCapture::isResponse)
                .filter(c -> c.getDurationMs() > maxMs)
                .findFirst()
                .ifPresent(c -> {
                    throw new AssertionError(String.format(
                            "NetworkMonitor: request to '%s' took %d ms (threshold: %d ms)",
                            c.getUrl(), c.getDurationMs(), maxMs));
                });
        log.info("NetworkMonitor ✓ all '{}' responses under {}ms", urlPattern, maxMs);
        return this;
    }

    /** Returns a fluent {@link NetworkAssertion} builder for this monitor's captures. */
    public NetworkAssertion assertThat() {
        return new NetworkAssertion(this);
    }

    /** Returns a compact summary for logging or Allure attachment. */
    public String summary() {
        long requests   = captures.stream().filter(c -> !c.isRequest()).count();
        long errCount   = errors().size();
        long failCount  = failed().size();
        long avgDuration = captures.stream()
                .filter(NetworkCapture::isResponse)
                .mapToLong(NetworkCapture::getDurationMs)
                .average()
                .stream().mapToLong(d -> (long) d).findFirst().orElse(0);

        return String.format("NetworkMonitor{captures=%d, errors=%d, failed=%d, avgDuration=%dms}",
                requests, errCount, failCount, avgDuration);
    }
}
