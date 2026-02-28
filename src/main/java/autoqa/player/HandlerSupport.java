package autoqa.player;

import autoqa.model.ElementInfo;
import autoqa.model.ElementLocator;
import autoqa.model.RecordedEvent;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

/**
 * Static helper utilities shared across {@link ActionHandler} implementations.
 *
 * <p>Centralises the repeated pattern of:
 * <pre>
 *   ElementLocator locator = resolver.resolve(elementInfo);
 *   By by = HandlerSupport.toBy(locator);
 *   wait.waitForXxx(by);
 *   WebElement el = driver.findElement(by);
 * </pre>
 */
final class HandlerSupport {

    private HandlerSupport() { }

    /**
     * Resolves an element, waits for it to be clickable, and returns the live
     * {@link WebElement}.
     */
    static WebElement resolveClickable(WebDriver driver, ElementInfo ei,
                                       LocatorResolver resolver, WaitStrategy wait) {
        ElementLocator locator = resolver.resolve(ei);
        By by = toBy(locator);
        wait.waitForClickable(by);
        return driver.findElement(by);
    }

    /**
     * Resolves an element, waits for it to be visible, and returns the live
     * {@link WebElement}.
     */
    static WebElement resolveVisible(WebDriver driver, ElementInfo ei,
                                     LocatorResolver resolver, WaitStrategy wait) {
        ElementLocator locator = resolver.resolve(ei);
        By by = toBy(locator);
        return wait.waitForVisible(by);
    }

    /**
     * Resolves an element, waits for it to be present in the DOM, and returns
     * the live {@link WebElement}.
     */
    static WebElement resolvePresent(WebDriver driver, ElementInfo ei,
                                     LocatorResolver resolver, WaitStrategy wait) {
        ElementLocator locator = resolver.resolve(ei);
        By by = toBy(locator);
        return wait.waitForPresent(by);
    }

    /**
     * Converts a resolved {@link ElementLocator} to a Selenium {@link By}.
     */
    static By toBy(ElementLocator locator) {
        return switch (locator.getStrategy()) {
            case ID    -> By.id(locator.getValue());
            case NAME  -> By.name(locator.getValue());
            case CSS   -> By.cssSelector(locator.getValue());
            case XPATH -> By.xpath(locator.getValue());
            default    -> By.xpath(locator.getValue()); // HEALED / TEXT fallback
        };
    }

    /**
     * Asserts that the event has a non-null element and returns it,
     * throwing {@link AutoQAException} with context if not.
     */
    static ElementInfo requireElement(RecordedEvent event, String handlerName) {
        if (!event.hasElement()) {
            throw new AutoQAException(handlerName + " event has no element: " + event);
        }
        return event.getElement();
    }
}
