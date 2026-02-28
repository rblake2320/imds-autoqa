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
 * <p>Each strategy is gated by {@code maxAttempts}: when all strategies up to
 * that limit have been exhausted an {@link AutoQAException} is thrown.
 *
 * <p>Both {@link #resolve} and {@link #findElement} share one internal lookup
 * path ({@link #locateFirst}) so the DOM is queried exactly once per call —
 * there is no second {@code driver.findElement} call.
 */
public class LocatorResolver {

    private static final Logger log = LoggerFactory.getLogger(LocatorResolver.class);

    private final WebDriver driver;
    private final WaitStrategy wait;
    private final int maxAttempts;

    /**
     * @param driver      active WebDriver session
     * @param wait        configured wait strategy (accepted for API symmetry; not
     *                    currently used at this level — callers use WaitStrategy directly)
     * @param maxAttempts maximum number of locator strategies to attempt
     *                    before raising an exception (must cover all 4 strategies)
     */
    public LocatorResolver(WebDriver driver, WaitStrategy wait, int maxAttempts) {
        this.driver = driver;
        this.wait = wait;
        this.maxAttempts = maxAttempts;
    }

    // ── Internal pair type ────────────────────────────────────────────────

    /**
     * Immutable pair of the winning {@link ElementLocator} and the live
     * {@link WebElement} it found — produced by a single DOM call so callers
     * do not need to repeat the lookup.
     */
    private record LocateMatch(ElementLocator locator, WebElement element) {}

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Determines the first locator strategy that successfully locates the
     * element in the current DOM.
     *
     * @param element recorded element identity with candidate locators
     * @return the winning {@link ElementLocator}
     * @throws AutoQAException if all strategies fail
     */
    public ElementLocator resolve(ElementInfo element) {
        return locateFirst(element).locator();
    }

    /**
     * Resolves the best locator and immediately returns the live
     * {@link WebElement} from the DOM — with a single DOM query.
     *
     * @param element recorded element identity
     * @return the live {@link WebElement}
     * @throws AutoQAException if all locator strategies fail
     */
    public WebElement findElement(ElementInfo element) {
        LocateMatch m = locateFirst(element);
        log.debug("Located element with {}: {}", m.locator().getStrategy(), m.locator().getValue());
        return m.element();
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    /**
     * Shared resolution path used by both {@link #resolve} and {@link #findElement}.
     * Tries ID → Name → CSS → XPath in order, each counted against
     * {@code maxAttempts}.  Uses {@code <} so that when {@code maxAttempts == 4}
     * all four strategies are reachable.
     */
    private LocateMatch locateFirst(ElementInfo element) {
        int attempts = 0;

        // ── 1. ID ──────────────────────────────────────────────────────
        if (isUsable(element.getId()) && attempts < maxAttempts) {
            attempts++;
            log.debug("Trying [ID]: {}", element.getId());
            LocateMatch m = tryLocator(Strategy.ID, By.id(element.getId()));
            if (m != null) return m;
        }

        // ── 2. Name ────────────────────────────────────────────────────
        if (isUsable(element.getName()) && attempts < maxAttempts) {
            attempts++;
            log.debug("Trying [NAME]: {}", element.getName());
            LocateMatch m = tryLocator(Strategy.NAME, By.name(element.getName()));
            if (m != null) return m;
        }

        // ── 3. CSS ─────────────────────────────────────────────────────
        if (isUsable(element.getCss()) && attempts < maxAttempts) {
            attempts++;
            log.debug("Trying [CSS]: {}", element.getCss());
            LocateMatch m = tryLocator(Strategy.CSS, By.cssSelector(element.getCss()));
            if (m != null) return m;
        }

        // ── 4. XPath ───────────────────────────────────────────────────
        if (isUsable(element.getXpath()) && attempts < maxAttempts) {
            attempts++;
            log.debug("Trying [XPATH]: {}", element.getXpath());
            LocateMatch m = tryLocator(Strategy.XPATH, By.xpath(element.getXpath()));
            if (m != null) return m;
        }

        // All strategies exhausted
        throw new AutoQAException(String.format(
                "All locator strategies failed for element: %s " +
                "(tried up to %d strategies — id='%s', name='%s', css='%s', xpath='%s')",
                element, maxAttempts,
                element.getId(), element.getName(), element.getCss(), element.getXpath()));
    }

    /**
     * Attempts to locate an element using the supplied {@link By} strategy.
     * Returns a {@link LocateMatch} containing both the locator metadata and
     * the live element — one DOM call, no second lookup.
     *
     * @return a populated {@link LocateMatch} on success, or {@code null}
     *         if the element was not found
     */
    private LocateMatch tryLocator(Strategy strategy, By by) {
        try {
            WebElement el = driver.findElement(by);
            return new LocateMatch(new ElementLocator(strategy, byValue(strategy, by)), el);
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

    /** Returns true only when the strategy value is non-null and non-blank. */
    private static boolean isUsable(String value) {
        return value != null && !value.isBlank();
    }
}
