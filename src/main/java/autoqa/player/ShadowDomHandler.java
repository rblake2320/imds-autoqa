package autoqa.player;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Shadow DOM element resolution for modern web applications.
 *
 * <p>Many modern web components (Material Web, Lit, Stencil, custom elements) use
 * Shadow DOM to encapsulate their internals. Standard Selenium locators cannot pierce
 * shadow roots — this handler provides JavaScript-based deep querying.
 *
 * <h3>Locator syntax supported</h3>
 * <ul>
 *   <li>{@code shadow://my-component >>> button.submit} — pierce one shadow root</li>
 *   <li>{@code shadow://outer-host >>> inner-host >>> input[type=text]} — chain multiple</li>
 *   <li>{@code shadow://host-selector} — just select the shadow host element</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * WebElement el = ShadowDomHandler.find(driver, "my-app >>> login-form >>> button#submit");
 *
 * // Or pierce from a known host element:
 * WebElement host = driver.findElement(By.tagName("my-app"));
 * WebElement btn  = ShadowDomHandler.findInShadow(driver, host, "login-form >>> button#submit");
 * }</pre>
 *
 * <p>The {@code >>>} separator (CSS-inspired "deep combinator") is the IMDS AutoQA
 * convention. Selenium 4.16+ also supports native {@code By.css} with CSS shadow-piercing
 * in some configurations; this class works as a universal fallback for all Selenium 4 versions.
 */
public class ShadowDomHandler {

    private static final Logger log = LoggerFactory.getLogger(ShadowDomHandler.class);

    /** Separator used in shadow DOM locator chains. */
    public static final String SHADOW_SEPARATOR = ">>>";

    /** Prefix that marks a locator as a shadow DOM path. */
    public static final String SHADOW_PREFIX = "shadow://";

    private ShadowDomHandler() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the locator value uses the shadow DOM syntax.
     */
    public static boolean isShadowLocator(String locatorValue) {
        return locatorValue != null &&
               (locatorValue.startsWith(SHADOW_PREFIX) || locatorValue.contains(SHADOW_SEPARATOR));
    }

    /**
     * Finds an element by traversing through one or more shadow roots.
     *
     * @param driver        active WebDriver
     * @param shadowPath    chain of CSS selectors separated by {@code >>>}
     *                      (the {@code shadow://} prefix is stripped if present)
     * @return the target WebElement
     * @throws NoSuchElementException if any step in the chain fails
     */
    public static WebElement find(WebDriver driver, String shadowPath) {
        String path = shadowPath.startsWith(SHADOW_PREFIX)
                ? shadowPath.substring(SHADOW_PREFIX.length()).trim()
                : shadowPath.trim();

        String[] parts = path.split("\\s*>>>\\s*");
        if (parts.length == 0) {
            throw new NoSuchElementException("Shadow DOM path is empty: " + shadowPath);
        }

        // Start from document root, traverse shadow roots for each segment
        WebElement current = null;
        for (int i = 0; i < parts.length; i++) {
            String selector = parts[i].trim();
            if (i == 0) {
                // First selector: find the shadow host in the main DOM
                try {
                    current = driver.findElement(By.cssSelector(selector));
                } catch (Exception e) {
                    throw new NoSuchElementException(
                            "Shadow DOM: could not find shadow host '" + selector +
                            "' in main document. Path: " + shadowPath);
                }
            } else {
                // Subsequent selectors: query inside the previous element's shadow root
                current = findInShadow(driver, current, selector);
            }
        }
        return current;
    }

    /**
     * Finds an element inside the shadow root of {@code host} using a CSS selector.
     *
     * @param driver   active WebDriver
     * @param host     the shadow host element
     * @param selector CSS selector to search inside the shadow root
     * @return the matching WebElement
     * @throws NoSuchElementException if the element is not found
     */
    public static WebElement findInShadow(WebDriver driver, WebElement host, String selector) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Object result = js.executeScript(
                "return arguments[0].shadowRoot ? arguments[0].shadowRoot.querySelector(arguments[1]) : null;",
                host, selector);

        if (result == null) {
            throw new NoSuchElementException(
                    "Shadow DOM: no element matching '" + selector + "' found in shadow root of " +
                    describeElement(driver, host));
        }
        return (WebElement) result;
    }

    /**
     * Finds all elements inside the shadow root of {@code host} using a CSS selector.
     *
     * @return list of matching WebElements (empty if none found)
     */
    @SuppressWarnings("unchecked")
    public static List<WebElement> findAllInShadow(WebDriver driver, WebElement host, String selector) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Object result = js.executeScript(
                "if (!arguments[0].shadowRoot) return [];" +
                "return Array.from(arguments[0].shadowRoot.querySelectorAll(arguments[1]));",
                host, selector);

        if (result instanceof List<?> list) {
            return (List<WebElement>) list;
        }
        return new ArrayList<>();
    }

    /**
     * Finds an element using the deep shadow piercing {@code >>>} chain via JavaScript.
     * This approach works even with multiple levels of nested shadow DOMs.
     *
     * @param driver     active WebDriver
     * @param shadowPath CSS chain with {@code >>>} separators
     * @return the target WebElement, or {@code null} if not found
     */
    public static WebElement findDeep(WebDriver driver, String shadowPath) {
        String path = shadowPath.startsWith(SHADOW_PREFIX)
                ? shadowPath.substring(SHADOW_PREFIX.length()).trim()
                : shadowPath.trim();

        // Build a JS function that recursively pierces shadow roots
        String script = """
                function deepQuery(root, selectors) {
                    if (selectors.length === 0) return root;
                    var el = (root.shadowRoot || root).querySelector(selectors[0]);
                    if (!el) return null;
                    if (selectors.length === 1) return el;
                    return deepQuery(el, selectors.slice(1));
                }
                var parts = arguments[0].split(/\\s*>>>\\s*/);
                return deepQuery(document, parts);
                """;

        JavascriptExecutor js = (JavascriptExecutor) driver;
        Object result = js.executeScript(script, path);
        return result instanceof WebElement we ? we : null;
    }

    /**
     * Returns the text content of an element in a shadow DOM.
     * Useful when standard {@code element.getText()} returns empty for shadow DOM elements.
     */
    public static String getTextContent(WebDriver driver, WebElement shadowElement) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        Object text = js.executeScript("return arguments[0].textContent;", shadowElement);
        return text != null ? text.toString().trim() : "";
    }

    /**
     * Checks whether the given locator string targets a shadow DOM path and
     * attempts to resolve it, returning the element or throwing if not found.
     *
     * <p>Integrates with {@link LocatorResolver} — call this when the standard
     * locator strategies all fail.
     */
    public static WebElement resolveFromLocator(WebDriver driver, String locatorValue) {
        if (!isShadowLocator(locatorValue)) {
            throw new IllegalArgumentException("Not a shadow DOM locator: " + locatorValue);
        }
        log.debug("Resolving shadow DOM locator: {}", locatorValue);
        WebElement el = findDeep(driver, locatorValue);
        if (el == null) {
            throw new NoSuchElementException("Shadow DOM element not found: " + locatorValue);
        }
        return el;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private static String describeElement(WebDriver driver, WebElement el) {
        try {
            JavascriptExecutor js = (JavascriptExecutor) driver;
            Object tag = js.executeScript(
                    "return arguments[0].tagName + (arguments[0].id ? '#' + arguments[0].id : '');", el);
            return tag != null ? tag.toString().toLowerCase() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
