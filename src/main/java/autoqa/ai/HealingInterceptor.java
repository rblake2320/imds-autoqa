package autoqa.ai;

import autoqa.model.ElementInfo;
import autoqa.model.ElementLocator;
import autoqa.player.AutoQAException;
import autoqa.player.LocatorResolver;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorates {@link LocatorResolver} with LLM-based self-healing on
 * {@link NoSuchElementException} or {@link AutoQAException}.
 *
 * <p>Healing is attempted <em>once</em> per element lookup — there are no
 * infinite retry loops. The healing cascade is:
 * <ol>
 *   <li>Try {@link LocatorResolver#findElement} normally.</li>
 *   <li>On failure, ask the {@link LocatorHealer} LLM path.</li>
 *   <li>If the LLM returns {@code CANNOT_HEAL} or errors, fall back to
 *       {@link LocatorHealer#healByDomComparison}.</li>
 *   <li>If every strategy fails, throw {@link AutoQAException} wrapping the
 *       original exception.</li>
 * </ol>
 *
 * <p>All healing events are logged at {@code INFO} level through the
 * {@code autoqa.ai.HealingInterceptor} logger, which logback routes to
 * {@code logs/healing.log} as well as the console appender.
 */
public class HealingInterceptor {

    // logback.xml routes this logger to HEALING_FILE + CONSOLE (additivity=false)
    private static final Logger log = LoggerFactory.getLogger(HealingInterceptor.class);

    private final LocatorResolver resolver;
    private final LocatorHealer healer;
    private final WebDriver driver;

    /**
     * @param resolver the primary locator resolution strategy
     * @param healer   the LLM-backed healer (also exposes DOM comparison fallback)
     * @param driver   active WebDriver session used for page source and healed lookups
     */
    public HealingInterceptor(LocatorResolver resolver, LocatorHealer healer, WebDriver driver) {
        this.resolver = resolver;
        this.healer   = healer;
        this.driver   = driver;
    }

    /**
     * Finds the element described by {@code element}.
     *
     * <p>Delegates to {@link LocatorResolver} first. If the resolver throws, the
     * healing cascade is activated exactly once.
     *
     * @param element recorded element identity with candidate locators
     * @return the live {@link WebElement} from the DOM
     * @throws AutoQAException if the resolver fails and all healing strategies are
     *                         also exhausted
     */
    public WebElement findElement(ElementInfo element) {
        try {
            return resolver.findElement(element);
        } catch (AutoQAException | NoSuchElementException | StaleElementReferenceException originalEx) {
            log.warn("LocatorResolver failed for element: {}. Attempting LLM healing...", element);
            return attemptHealing(element, originalEx);
        }
    }

    // ── Private healing cascade ───────────────────────────────────────────

    /**
     * Runs the two-stage healing cascade and applies the winning locator.
     *
     * @param element    the element that could not be found by the resolver
     * @param originalEx the exception thrown by the resolver
     * @return the live {@link WebElement} located by the healed locator
     * @throws AutoQAException when both healing stages fail
     */
    private WebElement attemptHealing(ElementInfo element, RuntimeException originalEx) {
        String pageSource = driver.getPageSource();
        String currentUrl = driver.getCurrentUrl();

        // Stage 1: LLM-based healing
        LocatorHealer.HealingResult result = healer.heal(element, pageSource, currentUrl);

        // Stage 2: DOM comparison fallback
        if (!result.healed()) {
            log.info("LLM healing failed ({}), trying DOM comparison fallback", result.failureReason());
            result = healer.healByDomComparison(element, driver);
        }

        if (!result.healed()) {
            log.error("All healing strategies exhausted for element: {}", element);
            throw new AutoQAException(
                    "Element not found and healing failed. Original: " + originalEx.getMessage(),
                    originalEx);
        }

        // Build the Selenium By from the healed locator
        By healedBy = result.strategy() == ElementLocator.Strategy.XPATH
                ? By.xpath(result.locatorValue())
                : By.cssSelector(result.locatorValue());

        log.info("HEALING ATTEMPT | element={} | strategy={} | locator={} | url={}",
                element, result.strategy(), result.locatorValue(), currentUrl);

        // Apply the healed locator (single attempt — no loop)
        try {
            WebElement found = driver.findElement(healedBy);
            log.info("HEALING SUCCESS | element={} | healed locator: {}", element, result.locatorValue());
            return found;
        } catch (NoSuchElementException healEx) {
            log.error("HEALING FAILED | element={} | healed locator did not find element: {}",
                    element, result.locatorValue());
            throw new AutoQAException(
                    "Healed locator also failed. Tried: " + result.locatorValue()
                            + ". Original: " + originalEx.getMessage(),
                    originalEx);
        }
    }
}
