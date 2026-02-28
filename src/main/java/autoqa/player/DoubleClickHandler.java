package autoqa.player;

import autoqa.model.ElementInfo;
import autoqa.model.RecordedEvent;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code DOUBLE_CLICK} events.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Resolve the winning locator and wait for the element to be clickable.</li>
 *   <li>Perform a double-click using {@link Actions}.</li>
 * </ol>
 */
public class DoubleClickHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(DoubleClickHandler.class);

    @Override
    public void handle(WebDriver driver, RecordedEvent event, LocatorResolver resolver, WaitStrategy wait) {
        ElementInfo ei = HandlerSupport.requireElement(event, "DOUBLE_CLICK");
        WebElement element = HandlerSupport.resolveClickable(driver, ei, resolver, wait);
        log.info("Double-clicking element: {}", ei);
        new Actions(driver).doubleClick(element).perform();
    }
}
