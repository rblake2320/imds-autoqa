package autoqa.keyword;

import autoqa.model.CheckpointData;
import autoqa.model.ElementInfo;
import autoqa.model.ObjectRepository;
import autoqa.model.TestObject;
import autoqa.player.AutoQAException;
import autoqa.player.LocatorResolver;
import autoqa.player.WaitStrategy;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Built-in keyword library — the IMDS AutoQA equivalent of UFT One's
 * Keyword View built-in operations.
 *
 * <p>Each method here is a keyword action that can be driven by
 * {@link KeywordEngine}.  Keywords operate on elements resolved from the
 * Object Repository by logical name, or directly on the driver for
 * page-level operations.
 *
 * <h3>UFT One Parity Keywords</h3>
 * <pre>
 *  click           — left-click element (OR name or CSS/XPath via "locator" param)
 *  doubleClick     — double-click element
 *  rightClick      — right-click (context menu)
 *  typeText        — clear + sendKeys (param: value)
 *  appendText      — sendKeys without clear (param: value)
 *  pressKey        — send a Keys constant (param: key = ENTER, TAB, etc.)
 *  selectOption    — select dropdown by text/value/index
 *  verifyText      — assert element text equals/contains expected
 *  verifyVisible   — assert element is displayed
 *  verifyEnabled   — assert element is enabled
 *  verifyAttribute — assert element attribute value
 *  verifyTitle     — assert page title
 *  verifyUrl       — assert URL contains substring
 *  navigate        — driver.get(url)
 *  scrollTo        — scrollIntoView element
 *  hover           — moveToElement
 *  waitForElement  — explicit wait for element presence
 *  acceptAlert     — accept JS alert
 *  dismissAlert    — dismiss JS alert
 *  screenshot      — take screenshot (saved via EvidenceCollector if available)
 * </pre>
 */
public class KeywordLibrary {

    private static final Logger log = LoggerFactory.getLogger(KeywordLibrary.class);

    /** Registered keyword handlers: keyword name → (driver, action) → void */
    private final Map<String, BiConsumer<WebDriver, KeywordAction>> registry = new HashMap<>();

    private final ObjectRepository or;
    private final LocatorResolver  resolver;
    private final WaitStrategy     wait;

    public KeywordLibrary(WebDriver driver, ObjectRepository or, WaitStrategy wait) {
        this.or       = or;
        this.wait     = wait;
        this.resolver = new LocatorResolver(driver, wait, 3);
        register(driver);
    }

    // ── Execute ───────────────────────────────────────────────────────────────

    /**
     * Executes a {@link KeywordAction} against the current browser session.
     *
     * @throws AutoQAException if the keyword is unknown or execution fails
     */
    public void execute(WebDriver driver, KeywordAction action) {
        String kw = action.getKeyword();
        BiConsumer<WebDriver, KeywordAction> handler = registry.get(kw.toLowerCase());
        if (handler == null) {
            throw new AutoQAException("Unknown keyword: '" + kw
                    + "'. Registered: " + registry.keySet());
        }
        log.info("Keyword: {} target={}", kw, action.getTarget());
        handler.accept(driver, action);
    }

    /** True if the keyword name is registered in this library. */
    public boolean hasKeyword(String keyword) {
        return keyword != null && registry.containsKey(keyword.toLowerCase());
    }

    // ── Registration ─────────────────────────────────────────────────────────

    private void register(WebDriver driver) {
        // Element interactions
        kw("click",        (d, a) -> findEl(a).click());
        kw("doubleclick",  (d, a) -> new Actions(d).doubleClick(findEl(a)).perform());
        kw("rightclick",   (d, a) -> new Actions(d).contextClick(findEl(a)).perform());
        kw("hover",        (d, a) -> new Actions(d).moveToElement(findEl(a)).perform());
        kw("scrollto",     (d, a) -> ((JavascriptExecutor) d)
                .executeScript("arguments[0].scrollIntoView(true);", findEl(a)));

        // Text input
        kw("typetext",  (d, a) -> {
            WebElement el = findEl(a);
            el.clear();
            el.sendKeys(a.param("value", ""));
        });
        kw("appendtext", (d, a) -> findEl(a).sendKeys(a.param("value", "")));
        kw("presskey",   (d, a) -> {
            String keyName = a.param("key", "ENTER").toUpperCase();
            Keys k;
            try { k = Keys.valueOf(keyName); }
            catch (IllegalArgumentException e) {
                throw new AutoQAException("Unknown key name: " + keyName, e);
            }
            if (a.getTarget() != null) findEl(a).sendKeys(k);
            else new Actions(d).sendKeys(k).perform();
        });

        // Dropdowns
        kw("selectoption", (d, a) -> {
            Select sel = new Select(findEl(a));
            String text  = a.param("text",  null);
            String value = a.param("value", null);
            String index = a.param("index", null);
            if (text  != null) sel.selectByVisibleText(text);
            else if (value != null) sel.selectByValue(value);
            else if (index != null) sel.selectByIndex(Integer.parseInt(index));
            else throw new AutoQAException("selectOption requires 'text', 'value', or 'index' param");
        });

        // Verifications (checkpoint equivalents)
        kw("verifytext", (d, a) -> {
            String actual   = findEl(a).getText();
            String expected = a.param("expected", "");
            String mode     = a.param("mode", "equals").toLowerCase();
            boolean pass = switch (mode) {
                case "contains"   -> actual.contains(expected);
                case "startswith" -> actual.startsWith(expected);
                default           -> actual.equals(expected);
            };
            if (!pass) throw new AutoQAException(
                    "verifyText failed: expected [" + expected + "] (" + mode + ") but got [" + actual + "]");
        });
        kw("verifyvisible",   (d, a) -> { if (!findEl(a).isDisplayed())
            throw new AutoQAException("verifyVisible failed: element not displayed — " + a.getTarget()); });
        kw("verifyenabled",   (d, a) -> { if (!findEl(a).isEnabled())
            throw new AutoQAException("verifyEnabled failed: element disabled — " + a.getTarget()); });
        kw("verifyattribute", (d, a) -> {
            String attrName = a.param("attribute", "value");
            String actual   = findEl(a).getAttribute(attrName);
            String expected = a.param("expected", "");
            if (!expected.equals(actual))
                throw new AutoQAException("verifyAttribute[" + attrName + "] failed: "
                        + "expected [" + expected + "] but got [" + actual + "]");
        });
        kw("verifytitle", (d, a) -> {
            String actual   = d.getTitle();
            String expected = a.param("expected", "");
            String mode     = a.param("mode", "contains").toLowerCase();
            boolean pass = switch (mode) {
                case "equals" -> actual.equals(expected);
                default       -> actual.contains(expected);
            };
            if (!pass) throw new AutoQAException(
                    "verifyTitle failed: expected [" + expected + "] (" + mode + ") in [" + actual + "]");
        });
        kw("verifyurl", (d, a) -> {
            String actual   = d.getCurrentUrl();
            String expected = a.param("expected", "");
            if (!actual.contains(expected))
                throw new AutoQAException("verifyUrl failed: [" + expected + "] not in [" + actual + "]");
        });

        // Navigation
        kw("navigate", (d, a) -> {
            String url = a.param("url", a.getTarget());
            if (url == null || url.isBlank())
                throw new AutoQAException("navigate keyword requires 'url' param or target");
            d.get(url);
            wait.waitForPageLoad();
        });

        // Alerts
        kw("acceptalert",  (d, a) -> wait.waitForAlertPresent().accept());
        kw("dismissalert", (d, a) -> wait.waitForAlertPresent().dismiss());

        // Waits
        kw("waitforelement", (d, a) -> findEl(a)); // findEl already calls wait internally
    }

    private void kw(String name, BiConsumer<WebDriver, KeywordAction> handler) {
        registry.put(name.toLowerCase(), handler);
    }

    // ── Element resolution ────────────────────────────────────────────────────

    private WebElement findEl(KeywordAction action) {
        String target  = action.getTarget();
        String locator = action.param("locator", null);

        ElementInfo ei;

        // 1. Named OR object
        if (target != null && or != null) {
            TestObject obj = or.find(target);
            if (obj != null) {
                ei = obj.toElementInfo();
                return resolver.findElement(ei);
            }
        }

        // 2. Inline locator param (CSS or XPath)
        if (locator != null) {
            ei = new ElementInfo();
            if (locator.startsWith("//") || locator.startsWith("(//")) {
                ei.setXpath(locator);
            } else {
                ei.setCss(locator);
            }
            return resolver.findElement(ei);
        }

        // 3. Target treated as CSS selector as last resort
        if (target != null) {
            ei = new ElementInfo();
            ei.setCss(target);
            return resolver.findElement(ei);
        }

        throw new AutoQAException("Keyword '" + action.getKeyword() + "' has no target or locator");
    }
}
