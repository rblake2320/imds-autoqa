package autoqa.player;

import autoqa.model.ElementInfo;
import autoqa.model.ElementLocator;
import autoqa.model.ElementLocator.Strategy;

import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the best working locator for a recorded {@link ElementInfo} by
 * trying strategies in priority order: ID → Name → CSS → XPath.
 *
 * <p>Each strategy is attempted up to {@code maxAttempts} times before
 * moving to the next one. If all strategies are exhausted an
 * {@link AutoQAException} is thrown with full context.
 */
public class LocatorResolver {

    private static final Logger log = LoggerFactory.getLogger(LocatorResolver.class);

    private final WebDriver driver;
    private final WaitStrategy wait;
    private final int maxAttempts;

    /**
     * @param driver      active WebDriver session
     * @param wait        configured wait strategy (used to wait for presence)
     * @param maxAttempts maximum number of locator strategies to attempt
     *                    before raising an exception
     */
    public LocatorResolver(WebDriver driver, WaitStrategy wait, int maxAttempts) {
        this.driver = driver;
        this.wait = wait;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Determines the first locator strategy that successfully locates the
     * element in the current DOM.
     *
     * @param element recorded element identity with candidate locators
     * @return the winning {@link ElementLocator}
     * @throws AutoQAException if all strategies fail
     */
    public ElementLocator resolve(ElementInfo element) {
        int attempts = 0;

        // ── 1. ID ──────────────────────────────────────────────────────
        if (isUsable(element.getId()) && attempts < maxAttempts) {
            attempts++;
            log.debug("Trying [ID]: {}", element.getId());
            ElementLocator found = tryLocator(Strategy.ID, By.id(element.getId()));
            if (found != null) return found;
        }

        // ── 2. Name ────────────────────────────────────────────────────
        if (isUsable(element.getName()) && attempts < maxAttempts) {
            attempts++;
            log.debug("Trying [NAME]: {}", element.getName());
            ElementLocator found = tryLocator(Strategy.NAME, By.name(element.getName()));
            if (found != null) return found;
        }

        // ── 3. CSS ─────────────────────────────────────────────────────
        if (isUsable(element.getCss()) && attempts < maxAttempts) {
            attempts++;
            log.debug("Trying [CSS]: {}", element.getCss());
            ElementLocator found = tryLocator(Strategy.CSS, By.cssSelector(element.getCss()));
            if (found != null) return found;
        }

        // ── 4. XPath ───────────────────────────────────────────────────
        if (isUsable(element.getXpath()) && attempts < maxAttempts) {
            attempts++;
            log.debug("Trying [XPATH]: {}", element.getXpath());
            ElementLocator found = tryLocator(Strategy.XPATH, By.xpath(element.getXpath()));
            if (found != null) return found;
        }

        // All strategies exhausted
        throw new AutoQAException(String.format(
                "All locator strategies failed for element: %s " +
                "(tried up to %d strategies — id='%s', name='%s', css='%s', xpath='%s')",
                element, maxAttempts,
                element.getId(), element.getName(), element.getCss(), element.getXpath()));
    }

    /**
     * Resolves the best locator and immediately returns the live
     * {@link WebElement} from the DOM.
     *
     * @param element recorded element identity
     * @return the live {@link WebElement}
     * @throws AutoQAException if all locator strategies fail
     */
    public WebElement findElement(ElementInfo element) {
        ElementLocator locator = resolve(element);
        By by = toBy(locator);
        log.debug("Located element with {}: {}", locator.getStrategy(), locator.getValue());
        return driver.findElement(by);
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    /**
     * Attempts to locate an element using the supplied {@link By} strategy.
     *
     * @return a populated {@link ElementLocator} on success, or {@code null}
     *         if the element was not found
     */
    private ElementLocator tryLocator(Strategy strategy, By by) {
        try {
            driver.findElement(by);   // presence check — throws if absent
            return new ElementLocator(strategy, byValue(strategy, by));
        } catch (NoSuchElementException e) {
            log.debug("[{}] not found: {}", strategy, by);
            return null;
        }
    }

    /** Extracts the raw string value from a {@link By} for storage. */
    private String byValue(Strategy strategy, By by) {
        // By.toString() returns e.g. "By.id: foo" — split after the first ": "
        String raw = by.toString();
        int colonSpace = raw.indexOf(": ");
        return colonSpace >= 0 ? raw.substring(colonSpace + 2) : raw;
    }

    /** Converts a resolved {@link ElementLocator} back to a Selenium {@link By}. */
    private By toBy(ElementLocator locator) {
        return switch (locator.getStrategy()) {
            case ID    -> By.id(locator.getValue());
            case NAME  -> By.name(locator.getValue());
            case CSS   -> By.cssSelector(locator.getValue());
            case XPATH -> By.xpath(locator.getValue());
            default    -> By.xpath(locator.getValue());  // HEALED / TEXT fallback
        };
    }

    /** Returns true only when the strategy value is non-null and non-blank. */
    private static boolean isUsable(String value) {
        return value != null && !value.isBlank();
    }
}
