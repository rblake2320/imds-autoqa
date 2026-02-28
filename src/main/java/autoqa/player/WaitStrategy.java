package autoqa.player;

import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Set;

/**
 * Centralises all explicit wait logic for the player. Implicit waits are
 * intentionally NEVER set — all waits go through this class so timeouts
 * are deterministic and logged uniformly.
 */
public class WaitStrategy {

    private static final Logger log = LoggerFactory.getLogger(WaitStrategy.class);

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final int timeoutSec;

    /**
     * @param driver     active WebDriver session
     * @param timeoutSec maximum time to wait for any condition
     */
    public WaitStrategy(WebDriver driver, int timeoutSec) {
        this.driver = driver;
        this.timeoutSec = timeoutSec;
        this.wait = new WebDriverWait(driver, Duration.ofSeconds(timeoutSec));
    }

    // ── Element presence / visibility / clickability ────────────────────

    /**
     * Waits until the element is present in the DOM (not necessarily visible).
     *
     * @param locator Selenium {@link By} locator
     * @return the located {@link WebElement}
     * @throws AutoQAException if the element is not present within the timeout
     */
    public WebElement waitForPresent(By locator) {
        log.debug("Waiting up to {}s for element PRESENT: {}", timeoutSec, locator);
        try {
            return wait.until(ExpectedConditions.presenceOfElementLocated(locator));
        } catch (Exception e) {
            throw new AutoQAException(
                    "Timed out after " + timeoutSec + "s waiting for element to be present: " + locator, e);
        }
    }

    /**
     * Waits until the element is both present in the DOM and visible on screen.
     *
     * @param locator Selenium {@link By} locator
     * @return the visible {@link WebElement}
     * @throws AutoQAException if the element is not visible within the timeout
     */
    public WebElement waitForVisible(By locator) {
        log.debug("Waiting up to {}s for element VISIBLE: {}", timeoutSec, locator);
        try {
            return wait.until(ExpectedConditions.visibilityOfElementLocated(locator));
        } catch (Exception e) {
            throw new AutoQAException(
                    "Timed out after " + timeoutSec + "s waiting for element to be visible: " + locator, e);
        }
    }

    /**
     * Waits until the element is visible and enabled (ready to receive input).
     *
     * @param locator Selenium {@link By} locator
     * @return the clickable {@link WebElement}
     * @throws AutoQAException if the element is not clickable within the timeout
     */
    public WebElement waitForClickable(By locator) {
        log.debug("Waiting up to {}s for element CLICKABLE: {}", timeoutSec, locator);
        try {
            return wait.until(ExpectedConditions.elementToBeClickable(locator));
        } catch (Exception e) {
            throw new AutoQAException(
                    "Timed out after " + timeoutSec + "s waiting for element to be clickable: " + locator, e);
        }
    }

    // ── Page-level conditions ───────────────────────────────────────────

    /**
     * Waits until the browser reports {@code document.readyState == 'complete'}.
     *
     * @throws AutoQAException if the page does not finish loading within the timeout
     */
    public void waitForPageLoad() {
        log.debug("Waiting up to {}s for page load (document.readyState == complete)", timeoutSec);
        try {
            wait.until(d -> {
                Object state = ((JavascriptExecutor) d)
                        .executeScript("return document.readyState");
                return "complete".equals(state);
            });
        } catch (Exception e) {
            throw new AutoQAException(
                    "Timed out after " + timeoutSec + "s waiting for page to finish loading", e);
        }
    }

    /**
     * Waits until the current URL contains the given fragment.
     *
     * @param fragment substring expected in the URL
     * @throws AutoQAException if the URL does not contain the fragment within the timeout
     */
    public void waitForUrlContains(String fragment) {
        log.debug("Waiting up to {}s for URL to contain: '{}'", timeoutSec, fragment);
        try {
            wait.until(ExpectedConditions.urlContains(fragment));
        } catch (Exception e) {
            throw new AutoQAException(
                    "Timed out after " + timeoutSec + "s waiting for URL to contain: '" + fragment + "'", e);
        }
    }

    // ── WebElement-accepting overloads ──────────────────────────────────

    /**
     * Waits until a specific {@link WebElement} (already located) is visible.
     *
     * @param element a WebElement already in the DOM
     * @return the same element once visible
     * @throws AutoQAException if the element is not visible within the timeout
     */
    public WebElement waitForVisible(WebElement element) {
        log.debug("Waiting up to {}s for WebElement to be VISIBLE", timeoutSec);
        try {
            return wait.until(ExpectedConditions.visibilityOf(element));
        } catch (Exception e) {
            throw new AutoQAException(
                    "Timed out after " + timeoutSec + "s waiting for WebElement to be visible", e);
        }
    }

    /**
     * Waits until a specific {@link WebElement} (already located) is visible
     * and enabled (clickable).
     *
     * @param element a WebElement already in the DOM
     * @return the same element once clickable
     * @throws AutoQAException if the element is not clickable within the timeout
     */
    public WebElement waitForClickable(WebElement element) {
        log.debug("Waiting up to {}s for WebElement to be CLICKABLE", timeoutSec);
        try {
            return wait.until(ExpectedConditions.elementToBeClickable(element));
        } catch (Exception e) {
            throw new AutoQAException(
                    "Timed out after " + timeoutSec + "s waiting for WebElement to be clickable", e);
        }
    }

    /**
     * Waits until a specific {@link WebElement} (already located) is present
     * (not stale) and returns it. Uses visibility as a proxy for presence since
     * Selenium's {@code ExpectedConditions} does not provide a staleness-free
     * presence check for an already-found element.
     *
     * @param element a WebElement already in the DOM
     * @return the same element
     * @throws AutoQAException if the element becomes stale within the timeout
     */
    public WebElement waitForPresent(WebElement element) {
        log.debug("Waiting up to {}s for WebElement to be PRESENT (not stale)", timeoutSec);
        try {
            return wait.until(ExpectedConditions.visibilityOf(element));
        } catch (Exception e) {
            throw new AutoQAException(
                    "Timed out after " + timeoutSec + "s waiting for WebElement to be present/visible", e);
        }
    }

    // ── Dialog conditions ───────────────────────────────────────────────

    /**
     * Waits until a JavaScript alert/confirm/prompt dialog is present.
     *
     * @return the {@link Alert} ready for interaction
     * @throws AutoQAException if no alert appears within the timeout
     */
    public Alert waitForAlertPresent() {
        log.debug("Waiting up to {}s for alert to be present", timeoutSec);
        try {
            return wait.until(ExpectedConditions.alertIsPresent());
        } catch (Exception e) {
            throw new AutoQAException(
                    "Timed out after " + timeoutSec + "s waiting for alert to be present", e);
        }
    }

    // ── Window conditions ───────────────────────────────────────────────

    /**
     * Waits until a new browser window or tab appears beyond those in
     * {@code knownHandles}, then returns the handle of the newly opened window.
     *
     * @param knownHandles window handles present before the action that
     *                     triggered the new window
     * @return the handle of the newly opened window
     * @throws AutoQAException if no new window appears within the timeout
     */
    public String waitForNewWindow(Set<String> knownHandles) {
        log.debug("Waiting up to {}s for new window (known handles: {})", timeoutSec, knownHandles.size());
        try {
            wait.until(d -> d.getWindowHandles().size() > knownHandles.size());
        } catch (Exception e) {
            throw new AutoQAException(
                    "Timed out after " + timeoutSec + "s waiting for a new browser window", e);
        }
        // Condition satisfied — find the new handle
        return driver.getWindowHandles().stream()
                .filter(h -> !knownHandles.contains(h))
                .findFirst()
                .orElseThrow(() -> new AutoQAException(
                        "New window expected but no unknown handle found"));
    }
}
