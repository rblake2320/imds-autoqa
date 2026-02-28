package autoqa.player;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Advanced wait utilities beyond Selenium's built-in {@link ExpectedConditions}.
 *
 * <p>Provides framework-aware waits for Angular, React, Vue, and jQuery applications,
 * plus network idle detection, animation completion, and other modern SPA patterns.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Wait for Angular to finish all pending HTTP requests
 * SmartWait.forAngular(driver);
 *
 * // Wait for React hydration to complete
 * SmartWait.forReactIdle(driver);
 *
 * // Wait for all CSS animations to stop
 * SmartWait.forAnimationsComplete(driver);
 *
 * // Wait for network to be idle for 500ms
 * SmartWait.forNetworkIdle(driver, 500);
 *
 * // Wait until URL matches a pattern
 * SmartWait.forUrl(driver, "dashboard", 10);
 *
 * // Wait for exactly 3 elements
 * SmartWait.forElementCount(driver, By.cssSelector(".item"), 3, 10);
 * }</pre>
 */
public final class SmartWait {

    private static final Logger log = LoggerFactory.getLogger(SmartWait.class);

    /** Default timeout for all smart waits (seconds). */
    public static final int DEFAULT_TIMEOUT_SEC = 30;

    /** Default polling interval (milliseconds). */
    public static final int DEFAULT_POLL_MS = 200;

    private SmartWait() {}

    // ── Framework-specific waits ──────────────────────────────────────────────

    /**
     * Waits for AngularJS / Angular to have no pending HTTP requests or pending tasks.
     *
     * <p>Works with both AngularJS (1.x) and Angular 2+:
     * <ul>
     *   <li>AngularJS: checks {@code angular.element(document).injector().get('$http').pendingRequests.length === 0}</li>
     *   <li>Angular 2+: checks {@code window.getAllAngularTestabilities().every(t => t.isStable())}</li>
     * </ul>
     */
    public static void forAngular(WebDriver driver) {
        forAngular(driver, DEFAULT_TIMEOUT_SEC);
    }

    public static void forAngular(WebDriver driver, int timeoutSec) {
        log.debug("SmartWait: waiting for Angular to stabilise");
        new WebDriverWait(driver, Duration.ofSeconds(timeoutSec), Duration.ofMillis(DEFAULT_POLL_MS))
                .until(angularStable());
        log.debug("SmartWait: Angular is stable");
    }

    private static ExpectedCondition<Boolean> angularStable() {
        return d -> {
            JavascriptExecutor js = (JavascriptExecutor) d;
            try {
                // Angular 2+
                Object result = js.executeScript(
                        "if (window.getAllAngularTestabilities) {" +
                        "  return window.getAllAngularTestabilities().every(function(t) { return t.isStable(); });" +
                        "}" +
                        // AngularJS 1.x
                        "if (window.angular) {" +
                        "  var inj = angular.element(document).injector();" +
                        "  if (inj) { var http = inj.get('$http'); return http.pendingRequests.length === 0; }" +
                        "}" +
                        "return true;");
                return Boolean.TRUE.equals(result);
            } catch (Exception e) {
                return true; // Angular not present — don't block
            }
        };
    }

    /**
     * Waits for React to finish rendering (checks that no active fiber work-loop is running).
     * This is a best-effort check using internal React DevTools fiber hooks.
     */
    public static void forReactIdle(WebDriver driver) {
        forReactIdle(driver, DEFAULT_TIMEOUT_SEC);
    }

    public static void forReactIdle(WebDriver driver, int timeoutSec) {
        log.debug("SmartWait: waiting for React to be idle");
        new WebDriverWait(driver, Duration.ofSeconds(timeoutSec), Duration.ofMillis(DEFAULT_POLL_MS))
                .until(d -> {
                    JavascriptExecutor js = (JavascriptExecutor) d;
                    try {
                        // Check for React 18 concurrent mode via __reactFiber or fallback
                        Object result = js.executeScript(
                                "if (typeof __REACT_DEVTOOLS_GLOBAL_HOOK__ !== 'undefined' " +
                                "    && __REACT_DEVTOOLS_GLOBAL_HOOK__.getFiberRoots) {" +
                                "  var roots = __REACT_DEVTOOLS_GLOBAL_HOOK__.getFiberRoots(1);" +
                                "  if (roots && roots.size > 0) {" +
                                "    return [...roots].every(r => !r.callbackNode);" +
                                "  }" +
                                "}" +
                                "return true;");
                        return Boolean.TRUE.equals(result);
                    } catch (Exception e) {
                        return true;
                    }
                });
        log.debug("SmartWait: React is idle");
    }

    /**
     * Waits for jQuery AJAX requests to complete (jQuery.active === 0).
     */
    public static void forJQuery(WebDriver driver) {
        forJQuery(driver, DEFAULT_TIMEOUT_SEC);
    }

    public static void forJQuery(WebDriver driver, int timeoutSec) {
        log.debug("SmartWait: waiting for jQuery AJAX to complete");
        new WebDriverWait(driver, Duration.ofSeconds(timeoutSec), Duration.ofMillis(DEFAULT_POLL_MS))
                .until(d -> {
                    JavascriptExecutor js = (JavascriptExecutor) d;
                    try {
                        Object result = js.executeScript(
                                "return typeof jQuery !== 'undefined' ? jQuery.active === 0 : true;");
                        return Boolean.TRUE.equals(result);
                    } catch (Exception e) {
                        return true;
                    }
                });
        log.debug("SmartWait: jQuery AJAX complete");
    }

    // ── Page-state waits ──────────────────────────────────────────────────────

    /**
     * Waits for all CSS transitions and animations to complete by checking
     * {@code document.getAnimations()} (Web Animations API).
     */
    public static void forAnimationsComplete(WebDriver driver) {
        forAnimationsComplete(driver, DEFAULT_TIMEOUT_SEC);
    }

    public static void forAnimationsComplete(WebDriver driver, int timeoutSec) {
        log.debug("SmartWait: waiting for animations to complete");
        new WebDriverWait(driver, Duration.ofSeconds(timeoutSec), Duration.ofMillis(DEFAULT_POLL_MS))
                .until(d -> {
                    JavascriptExecutor js = (JavascriptExecutor) d;
                    try {
                        Object result = js.executeScript(
                                "return document.getAnimations().every(a => a.playState !== 'running');");
                        return Boolean.TRUE.equals(result);
                    } catch (Exception e) {
                        return true;
                    }
                });
        log.debug("SmartWait: animations complete");
    }

    /**
     * Waits for the browser network to be idle (no pending XHR/Fetch for at least {@code idleMs}).
     * Installs a temporary XMLHttpRequest/fetch interceptor to track pending requests.
     *
     * @param idleMs milliseconds of network silence required (e.g. 500)
     */
    public static void forNetworkIdle(WebDriver driver, long idleMs) {
        forNetworkIdle(driver, idleMs, DEFAULT_TIMEOUT_SEC);
    }

    public static void forNetworkIdle(WebDriver driver, long idleMs, int timeoutSec) {
        log.debug("SmartWait: waiting for network idle ({}ms quiet)", idleMs);
        JavascriptExecutor js = (JavascriptExecutor) driver;

        // Install counters if not already present
        js.executeScript("""
                if (!window.__iqaNetPending) {
                    window.__iqaNetPending = 0;
                    window.__iqaLastActivity = Date.now();
                    var origOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function() {
                        window.__iqaNetPending++;
                        window.__iqaLastActivity = Date.now();
                        this.addEventListener('loadend', function() {
                            window.__iqaNetPending = Math.max(0, window.__iqaNetPending - 1);
                            window.__iqaLastActivity = Date.now();
                        });
                        origOpen.apply(this, arguments);
                    };
                    var origFetch = window.fetch;
                    if (origFetch) {
                        window.fetch = function() {
                            window.__iqaNetPending++;
                            window.__iqaLastActivity = Date.now();
                            return origFetch.apply(this, arguments).finally(function() {
                                window.__iqaNetPending = Math.max(0, window.__iqaNetPending - 1);
                                window.__iqaLastActivity = Date.now();
                            });
                        };
                    }
                }
                """);

        new WebDriverWait(driver, Duration.ofSeconds(timeoutSec), Duration.ofMillis(DEFAULT_POLL_MS))
                .until(d -> {
                    try {
                        Object pending = js.executeScript("return window.__iqaNetPending || 0;");
                        Object lastAct = js.executeScript("return window.__iqaLastActivity || 0;");
                        long pendingCount = pending instanceof Number n ? n.longValue() : 0;
                        long lastActivity = lastAct instanceof Number n ? n.longValue() : 0;
                        long idle = System.currentTimeMillis() - lastActivity;
                        return pendingCount == 0 && idle >= idleMs;
                    } catch (Exception e) {
                        return true;
                    }
                });
        log.debug("SmartWait: network idle confirmed");
    }

    /**
     * Waits for the document to be fully loaded (readyState === 'complete').
     */
    public static void forDocumentReady(WebDriver driver) {
        forDocumentReady(driver, DEFAULT_TIMEOUT_SEC);
    }

    public static void forDocumentReady(WebDriver driver, int timeoutSec) {
        new WebDriverWait(driver, Duration.ofSeconds(timeoutSec), Duration.ofMillis(DEFAULT_POLL_MS))
                .until(d -> {
                    JavascriptExecutor js = (JavascriptExecutor) d;
                    return "complete".equals(js.executeScript("return document.readyState;"));
                });
    }

    // ── Navigation waits ──────────────────────────────────────────────────────

    /**
     * Waits for the current URL to contain the given substring.
     */
    public static void forUrl(WebDriver driver, String urlSubstring, int timeoutSec) {
        log.debug("SmartWait: waiting for URL to contain '{}'", urlSubstring);
        new WebDriverWait(driver, Duration.ofSeconds(timeoutSec))
                .until(ExpectedConditions.urlContains(urlSubstring));
    }

    /**
     * Waits for the current URL to match the given regex pattern.
     */
    public static void forUrlPattern(WebDriver driver, String regex, int timeoutSec) {
        log.debug("SmartWait: waiting for URL matching /{}/", regex);
        Pattern pattern = Pattern.compile(regex);
        new WebDriverWait(driver, Duration.ofSeconds(timeoutSec), Duration.ofMillis(DEFAULT_POLL_MS))
                .until(d -> pattern.matcher(d.getCurrentUrl()).find());
    }

    /**
     * Waits for the page title to contain the given substring.
     */
    public static void forTitle(WebDriver driver, String titleSubstring, int timeoutSec) {
        new WebDriverWait(driver, Duration.ofSeconds(timeoutSec))
                .until(ExpectedConditions.titleContains(titleSubstring));
    }

    // ── Element count waits ───────────────────────────────────────────────────

    /**
     * Waits until the number of elements matching {@code by} equals exactly {@code expectedCount}.
     */
    public static List<WebElement> forElementCount(WebDriver driver, By by, int expectedCount, int timeoutSec) {
        log.debug("SmartWait: waiting for exactly {} elements matching {}", expectedCount, by);
        return new WebDriverWait(driver, Duration.ofSeconds(timeoutSec), Duration.ofMillis(DEFAULT_POLL_MS))
                .until(d -> {
                    List<WebElement> els = d.findElements(by);
                    return els.size() == expectedCount ? els : null;
                });
    }

    /**
     * Waits until the number of elements matching {@code by} is at least {@code minCount}.
     */
    public static List<WebElement> forAtLeastElements(WebDriver driver, By by, int minCount, int timeoutSec) {
        return new WebDriverWait(driver, Duration.ofSeconds(timeoutSec), Duration.ofMillis(DEFAULT_POLL_MS))
                .until(d -> {
                    List<WebElement> els = d.findElements(by);
                    return els.size() >= minCount ? els : null;
                });
    }

    /**
     * Waits until the text content of an element changes from {@code staleText}.
     */
    public static String forTextChange(WebDriver driver, WebElement element, String staleText, int timeoutSec) {
        return new WebDriverWait(driver, Duration.ofSeconds(timeoutSec), Duration.ofMillis(DEFAULT_POLL_MS))
                .until(d -> {
                    String current = element.getText();
                    return !current.equals(staleText) ? current : null;
                });
    }

    /**
     * Waits until an element's attribute value contains the expected substring.
     */
    public static void forAttribute(WebDriver driver, WebElement element,
                                     String attribute, String expectedValue, int timeoutSec) {
        new WebDriverWait(driver, Duration.ofSeconds(timeoutSec), Duration.ofMillis(DEFAULT_POLL_MS))
                .until(d -> {
                    String val = element.getAttribute(attribute);
                    return val != null && val.contains(expectedValue);
                });
    }

    /**
     * Waits until a JavaScript expression evaluates to truthy.
     *
     * @param jsExpression JavaScript expression (must return truthy when ready)
     */
    public static void forJavaScript(WebDriver driver, String jsExpression, int timeoutSec) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        new WebDriverWait(driver, Duration.ofSeconds(timeoutSec), Duration.ofMillis(DEFAULT_POLL_MS))
                .until(d -> {
                    try {
                        Object result = js.executeScript("return (" + jsExpression + ");");
                        return result != null && !Boolean.FALSE.equals(result);
                    } catch (Exception e) {
                        return false;
                    }
                });
    }
}
