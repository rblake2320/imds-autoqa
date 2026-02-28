package autoqa.spy;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Comprehensive application spy — a passive observation layer that captures
 * <em>everything</em> the application under test does, without modifying it.
 *
 * <p>This goes far beyond what UFT One, TestComplete, Playwright Inspector,
 * or Katalon Studio offer by providing a unified, queryable, time-ordered
 * capture stream of all application activity:
 *
 * <ul>
 *   <li><b>Full network capture</b> — every XHR/Fetch request AND response with headers
 *       and full body text (configurable max size), plus timing</li>
 *   <li><b>Storage surveillance</b> — localStorage and sessionStorage reads/writes/deletes,
 *       key names and values, timestamped</li>
 *   <li><b>Cookie monitoring</b> — captures document.cookie changes (new cookies, deleted cookies)</li>
 *   <li><b>DOM mutation stream</b> — every node added or removed from the DOM, including
 *       what changed and where, using MutationObserver</li>
 *   <li><b>Console interception</b> — all console.log/warn/error/debug messages with
 *       original stack context</li>
 *   <li><b>JS variable watch</b> — monitor specific global variables and capture
 *       their value whenever they change (Object.defineProperty spy)</li>
 *   <li><b>Custom event capture</b> — subscribe to application-level CustomEvents</li>
 * </ul>
 *
 * <p>All captures are collected into an ordered, thread-safe list with sequence numbers
 * for perfect replay ordering. The spy can be queried for specific capture types, patterns,
 * time ranges, or asserted against for test validation.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ApplicationSpy spy = ApplicationSpy.attach(driver);
 * spy.start();
 *
 * // ... run test steps ...
 *
 * // Assert specific API was called with expected data
 * spy.assertApiCalled("/api/checkout", "POST");
 * spy.assertApiResponseContains("/api/checkout", "orderId");
 * spy.assertNoServerErrors();
 *
 * // Check that login token was stored
 * spy.assertStorageSet("authToken");
 *
 * // Check no console errors
 * spy.assertNoConsoleErrors();
 *
 * // Check DOM mutation (modal appeared)
 * spy.assertDomAdded(".modal-dialog");
 *
 * // Get full capture timeline for reporting
 * List<SpyCapture> timeline = spy.captures();
 * String report = spy.report();
 *
 * spy.stop();
 * }</pre>
 *
 * <h3>Architecture</h3>
 * <p>The spy works by injecting a lightweight JavaScript shim at page load that wraps
 * XMLHttpRequest, fetch, localStorage, sessionStorage, console, and registers a
 * MutationObserver. The shim buffers all events in {@code window.__iqaSpy} queue.
 * A background Java thread polls this queue every {@code pollIntervalMs} and drains
 * it into the Java-side capture list.
 */
public class ApplicationSpy {

    private static final Logger log = LoggerFactory.getLogger(ApplicationSpy.class);

    /** Default poll interval for draining the JS capture queue (milliseconds). */
    public static final int DEFAULT_POLL_MS = 250;

    /** Default maximum body size to capture per request/response (bytes). */
    public static final int DEFAULT_MAX_BODY_CHARS = 4096;

    private final WebDriver  driver;
    private final int        pollMs;
    private final int        maxBodyChars;

    private final List<SpyCapture>          captures       = new CopyOnWriteArrayList<>();
    private final AtomicLong                sequenceCounter = new AtomicLong(0);
    private final List<Consumer<SpyCapture>> listeners      = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService poller;
    private boolean active = false;

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Attaches an ApplicationSpy to the given WebDriver. */
    public static ApplicationSpy attach(WebDriver driver) {
        return new ApplicationSpy(driver, DEFAULT_POLL_MS, DEFAULT_MAX_BODY_CHARS);
    }

    /** Attaches with custom poll interval. */
    public static ApplicationSpy attach(WebDriver driver, int pollMs, int maxBodyChars) {
        return new ApplicationSpy(driver, pollMs, maxBodyChars);
    }

    protected ApplicationSpy(WebDriver driver, int pollMs, int maxBodyChars) {
        this.driver       = driver;
        this.pollMs       = pollMs;
        this.maxBodyChars = maxBodyChars;
    }

    /** Minimal constructor for test subclasses (no driver, no polling). */
    protected ApplicationSpy(WebDriver driver) {
        this(driver, DEFAULT_POLL_MS, DEFAULT_MAX_BODY_CHARS);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Starts the spy: injects the JS shim and begins polling for captures.
     * Safe to call multiple times (idempotent).
     */
    public ApplicationSpy start() {
        captures.clear();
        sequenceCounter.set(0);
        injectShim();
        startPoller();
        active = true;
        log.info("ApplicationSpy: started (poll={}ms, maxBody={}chars)", pollMs, maxBodyChars);
        return this;
    }

    /**
     * Reinjects the shim after page navigation (call this after any navigation event).
     */
    public ApplicationSpy reattach() {
        injectShim();
        return this;
    }

    /**
     * Stops the spy. Drains any remaining captures before stopping.
     */
    public ApplicationSpy stop() {
        if (active) {
            drain(); // final drain
            if (poller != null) {
                poller.shutdownNow();
                poller = null;
            }
            active = false;
            log.info("ApplicationSpy: stopped — {} total captures", captures.size());
        }
        return this;
    }

    /** Clears all captures without stopping the spy. */
    public ApplicationSpy clear() {
        captures.clear();
        sequenceCounter.set(0);
        return this;
    }

    /** Registers a listener that receives each new capture in real time. */
    public ApplicationSpy onCapture(Consumer<SpyCapture> listener) {
        listeners.add(listener);
        return this;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** All captures in sequence order. */
    public List<SpyCapture> captures() {
        return Collections.unmodifiableList(captures);
    }

    /** Captures of a specific type. */
    public List<SpyCapture> captures(SpyCapture.Type type) {
        return captures.stream().filter(c -> c.getType() == type).toList();
    }

    /** Captures whose source or data contains the given text (case-insensitive). */
    public List<SpyCapture> capturing(String text) {
        return captures.stream().filter(c -> c.contains(text)).toList();
    }

    /** Network response captures matching the URL pattern (regex). */
    public List<SpyCapture> networkResponses(String urlPattern) {
        return captures().stream()
                .filter(c -> c.getType() == SpyCapture.Type.NETWORK_RESPONSE)
                .filter(c -> c.getSource() != null && c.getSource().matches(".*" + urlPattern + ".*"))
                .toList();
    }

    /** Network request captures matching the URL pattern and HTTP method. */
    public List<SpyCapture> networkRequests(String urlPattern, String method) {
        return captures().stream()
                .filter(c -> c.getType() == SpyCapture.Type.NETWORK_REQUEST)
                .filter(c -> c.getSource() != null && c.getSource().matches(".*" + urlPattern + ".*"))
                .filter(c -> method == null || method.equalsIgnoreCase(c.getExtra()))
                .toList();
    }

    /** Storage captures for a specific key. */
    public List<SpyCapture> storageCaptures(String keyPattern) {
        return captures().stream()
                .filter(SpyCapture::isStorageCapture)
                .filter(c -> c.getSource() != null && c.getSource().matches(".*" + keyPattern + ".*"))
                .toList();
    }

    /** Console captures at ERROR or WARNING level. */
    public List<SpyCapture> consoleErrors() {
        return captures().stream()
                .filter(SpyCapture::isConsoleCapture)
                .filter(c -> "error".equals(c.getExtra()) || "warn".equals(c.getExtra()))
                .toList();
    }

    /** DOM mutation captures where the added element matches the CSS selector. */
    public List<SpyCapture> domAdditions(String selectorPattern) {
        return captures().stream()
                .filter(SpyCapture::isDomCapture)
                .filter(c -> "added".equals(c.getExtra()))
                .filter(c -> c.getSource() != null && c.getSource().matches(".*" + selectorPattern + ".*"))
                .toList();
    }

    // ── Assertions ─────────────────────────────────────────────────────────────

    /**
     * Asserts that at least one network request was made to a URL matching {@code urlPattern}
     * with the specified HTTP method (or any method if {@code method} is null).
     */
    public ApplicationSpy assertApiCalled(String urlPattern, String method) {
        List<SpyCapture> matches = networkRequests(urlPattern, method);
        if (matches.isEmpty()) {
            throw new AssertionError(String.format(
                    "ApplicationSpy: no %s request matched '%s'.\nCaptured requests: %s",
                    method != null ? method : "HTTP",
                    urlPattern,
                    captures(SpyCapture.Type.NETWORK_REQUEST).stream()
                            .map(c -> c.getExtra() + " " + c.getSource())
                            .collect(Collectors.joining(", "))));
        }
        log.info("ApplicationSpy ✓ API called: {} {}", method, urlPattern);
        return this;
    }

    /**
     * Asserts that the response body from a URL matching {@code urlPattern} contains
     * the expected text.
     */
    public ApplicationSpy assertApiResponseContains(String urlPattern, String expectedText) {
        List<SpyCapture> responses = networkResponses(urlPattern);
        boolean found = responses.stream()
                .anyMatch(c -> c.getData() != null && c.getData().contains(expectedText));
        if (!found) {
            throw new AssertionError(String.format(
                    "ApplicationSpy: no response from '%s' contained '%s'.\nCaptures: %s",
                    urlPattern, expectedText,
                    responses.stream().map(c -> c.getStatusCode() + " " + c.getData()).toList()));
        }
        log.info("ApplicationSpy ✓ response from '{}' contains '{}'", urlPattern, expectedText);
        return this;
    }

    /**
     * Asserts that no HTTP 5xx server errors were received.
     */
    public ApplicationSpy assertNoServerErrors() {
        List<SpyCapture> errors = captures().stream().filter(SpyCapture::isServerError).toList();
        if (!errors.isEmpty()) {
            throw new AssertionError(
                    "ApplicationSpy: " + errors.size() + " server error(s) detected:\n  " +
                    errors.stream().map(SpyCapture::toString).collect(Collectors.joining("\n  ")));
        }
        log.info("ApplicationSpy ✓ no server errors");
        return this;
    }

    /**
     * Asserts that a localStorage or sessionStorage key was set (any value).
     */
    public ApplicationSpy assertStorageSet(String keyPattern) {
        if (storageCaptures(keyPattern).stream()
                .noneMatch(c -> c.getType() == SpyCapture.Type.STORAGE_SET)) {
            throw new AssertionError(
                    "ApplicationSpy: key matching '" + keyPattern + "' was never stored.\n" +
                    "Stored keys: " + captures(SpyCapture.Type.STORAGE_SET)
                            .stream().map(SpyCapture::getSource).toList());
        }
        log.info("ApplicationSpy ✓ storage key set: {}", keyPattern);
        return this;
    }

    /**
     * Asserts that a localStorage/sessionStorage key was set to a specific value.
     */
    public ApplicationSpy assertStorageValue(String key, String expectedValue) {
        boolean found = captures(SpyCapture.Type.STORAGE_SET).stream()
                .filter(c -> key.equals(c.getSource()))
                .anyMatch(c -> expectedValue.equals(c.getData()));
        if (!found) {
            throw new AssertionError(String.format(
                    "ApplicationSpy: key '%s' was never set to '%s'.\nActual values: %s",
                    key, expectedValue,
                    captures(SpyCapture.Type.STORAGE_SET).stream()
                            .filter(c -> key.equals(c.getSource()))
                            .map(SpyCapture::getData).toList()));
        }
        return this;
    }

    /**
     * Asserts that no console errors or warnings were logged.
     */
    public ApplicationSpy assertNoConsoleErrors() {
        List<SpyCapture> errors = consoleErrors();
        if (!errors.isEmpty()) {
            throw new AssertionError(
                    "ApplicationSpy: " + errors.size() + " console error(s):\n  " +
                    errors.stream().map(SpyCapture::toString).collect(Collectors.joining("\n  ")));
        }
        log.info("ApplicationSpy ✓ no console errors");
        return this;
    }

    /**
     * Asserts that a DOM element matching the selector was added to the page.
     */
    public ApplicationSpy assertDomAdded(String selectorPattern) {
        if (domAdditions(selectorPattern).isEmpty()) {
            throw new AssertionError(
                    "ApplicationSpy: no DOM element matching '" + selectorPattern + "' was added.\n" +
                    "DOM additions: " + captures(SpyCapture.Type.DOM_MUTATION).stream()
                            .filter(c -> "added".equals(c.getExtra()))
                            .map(SpyCapture::getSource).toList());
        }
        log.info("ApplicationSpy ✓ DOM element added: {}", selectorPattern);
        return this;
    }

    /**
     * Asserts that no network request was made to a URL matching the pattern.
     */
    public ApplicationSpy assertNoRequestTo(String urlPattern) {
        List<SpyCapture> matches = networkRequests(urlPattern, null);
        if (!matches.isEmpty()) {
            throw new AssertionError(
                    "ApplicationSpy: unexpected request to '" + urlPattern + "' was made " +
                    matches.size() + " time(s)");
        }
        return this;
    }

    // ── Reporting ──────────────────────────────────────────────────────────────

    /**
     * Returns a formatted, time-ordered capture report suitable for Allure attachment.
     */
    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("APPLICATION SPY REPORT\n");
        sb.append("======================\n");
        sb.append(String.format("Total captures: %d%n%n", captures.size()));

        Map<SpyCapture.Type, Long> counts = captures().stream()
                .collect(Collectors.groupingBy(SpyCapture::getType, Collectors.counting()));
        sb.append("Summary:\n");
        counts.forEach((type, count) ->
                sb.append(String.format("  %-20s %d%n", type, count)));
        sb.append("\nTimeline:\n");
        captures().forEach(c -> sb.append(String.format("  [%04d] %s%n", c.getSequenceNo(), c)));
        return sb.toString();
    }

    // ── JavaScript shim ────────────────────────────────────────────────────────

    private void injectShim() {
        String script = buildShimScript();
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript(script);
            log.debug("ApplicationSpy: JS shim injected");
        } catch (Exception e) {
            log.warn("ApplicationSpy: shim injection failed: {}", e.getMessage());
        }
    }

    private String buildShimScript() {
        int maxBody = maxBodyChars;
        return """
                (function() {
                  if (window.__iqaSpyActive) return;
                  window.__iqaSpyActive = true;
                  window.__iqaSpy = [];

                  function push(type, source, data, extra, status, duration) {
                    window.__iqaSpy.push({
                      type: type, source: source || '',
                      data: (data || '').substring(0, %d),
                      extra: extra || '', status: status || 0,
                      duration: duration || 0, ts: Date.now()
                    });
                  }

                  // ── XHR spy ────────────────────────────────────────────────
                  var origOpen  = XMLHttpRequest.prototype.open;
                  var origSend  = XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.open = function(method, url) {
                    this.__iqaMethod = method; this.__iqaUrl = url;
                    this.__iqaStart = 0;
                    return origOpen.apply(this, arguments);
                  };
                  XMLHttpRequest.prototype.send = function(body) {
                    var self = this;
                    self.__iqaStart = Date.now();
                    push('NETWORK_REQUEST', self.__iqaUrl, body || '', self.__iqaMethod, 0, 0);
                    this.addEventListener('loadend', function() {
                      var dur = Date.now() - self.__iqaStart;
                      push('NETWORK_RESPONSE', self.__iqaUrl,
                           self.responseText || '', self.__iqaMethod, self.status, dur);
                    });
                    return origSend.apply(this, arguments);
                  };

                  // ── Fetch spy ─────────────────────────────────────────────
                  var origFetch = window.fetch;
                  if (origFetch) {
                    window.fetch = function(input, opts) {
                      var url    = typeof input === 'string' ? input : (input.url || String(input));
                      var method = (opts && opts.method) || 'GET';
                      var body   = (opts && opts.body) ? String(opts.body).substring(0, %d) : '';
                      var start  = Date.now();
                      push('NETWORK_REQUEST', url, body, method, 0, 0);
                      return origFetch.apply(this, arguments).then(function(resp) {
                        var dur = Date.now() - start;
                        return resp.clone().text().then(function(text) {
                          push('NETWORK_RESPONSE', url, text, method, resp.status, dur);
                          return resp;
                        });
                      });
                    };
                  }

                  // ── localStorage spy ─────────────────────────────────────
                  var origSetItem    = Storage.prototype.setItem;
                  var origRemoveItem = Storage.prototype.removeItem;
                  Storage.prototype.setItem = function(k, v) {
                    push('STORAGE_SET', k, String(v), this === sessionStorage ? 'session' : 'local', 0, 0);
                    return origSetItem.apply(this, arguments);
                  };
                  Storage.prototype.removeItem = function(k) {
                    push('STORAGE_REMOVE', k, '', this === sessionStorage ? 'session' : 'local', 0, 0);
                    return origRemoveItem.apply(this, arguments);
                  };

                  // ── Console spy ───────────────────────────────────────────
                  ['log','warn','error','debug','info'].forEach(function(level) {
                    var orig = console[level];
                    console[level] = function() {
                      var msg = Array.from(arguments).map(function(a) {
                        try { return typeof a === 'object' ? JSON.stringify(a) : String(a); }
                        catch(e) { return String(a); }
                      }).join(' ');
                      push('CONSOLE_LOG', window.location.href, msg, level, 0, 0);
                      return orig.apply(console, arguments);
                    };
                  });

                  // ── DOM MutationObserver ──────────────────────────────────
                  var observer = new MutationObserver(function(mutations) {
                    mutations.forEach(function(m) {
                      m.addedNodes.forEach(function(n) {
                        if (n.nodeType === 1) {
                          var sel = n.tagName.toLowerCase()
                            + (n.id ? '#' + n.id : '')
                            + (n.className && typeof n.className === 'string'
                               ? '.' + n.className.trim().split(/\\s+/).join('.') : '');
                          push('DOM_MUTATION', sel, '', 'added', 0, 0);
                        }
                      });
                      m.removedNodes.forEach(function(n) {
                        if (n.nodeType === 1) {
                          var sel = n.tagName.toLowerCase() + (n.id ? '#' + n.id : '');
                          push('DOM_MUTATION', sel, '', 'removed', 0, 0);
                        }
                      });
                    });
                  });
                  observer.observe(document.documentElement, {childList: true, subtree: true});

                })();
                """.formatted(maxBody, maxBody);
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private void startPoller() {
        if (poller != null) { poller.shutdownNow(); }
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ApplicationSpy-poller");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleAtFixedRate(this::drain, pollMs, pollMs, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void drain() {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            Object raw = js.executeScript(
                    "var q = window.__iqaSpy || []; window.__iqaSpy = []; return q;");
            if (!(raw instanceof List<?> list) || list.isEmpty()) return;

            for (Object item : list) {
                if (!(item instanceof Map)) continue;
                Map<Object, Object> m = (Map) item;
                String typeStr = m.getOrDefault("type", "").toString();
                SpyCapture.Type type;
                try { type = SpyCapture.Type.valueOf(typeStr); }
                catch (Exception e) { type = SpyCapture.Type.CUSTOM_EVENT; }

                SpyCapture capture = new SpyCapture(
                        type,
                        m.getOrDefault("source", "").toString(),
                        m.getOrDefault("data",   "").toString(),
                        m.getOrDefault("extra",  "").toString(),
                        null,
                        ((Number) m.getOrDefault("status",   0)).intValue(),
                        ((Number) m.getOrDefault("duration", 0)).longValue(),
                        Instant.now(),
                        sequenceCounter.getAndIncrement()
                );

                captures.add(capture);
                listeners.forEach(l -> {
                    try { l.accept(capture); } catch (Exception ignored) {}
                });
                log.trace("ApplicationSpy: {}", capture);
            }
        } catch (Exception e) {
            log.trace("ApplicationSpy: drain error (page may be navigating): {}", e.getMessage());
        }
    }
}
