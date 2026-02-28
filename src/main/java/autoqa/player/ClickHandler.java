package autoqa.player;

import autoqa.model.RecordedEvent;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code CLICK} events.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Resolve the winning locator via {@link LocatorResolver}.</li>
 *   <li>Wait for the element to be clickable via {@link WaitStrategy}.</li>
 *   <li>Perform the native {@link WebElement#click()}.</li>
 * </ol>
 */
public class ClickHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(ClickHandler.class);

    @Override
    public void handle(WebDriver driver, RecordedEvent event, LocatorResolver resolver, WaitStrategy wait) {
        autoqa.model.ElementInfo ei = HandlerSupport.requireElement(event, "CLICK");
        WebElement element = HandlerSupport.resolveClickable(driver, ei, resolver, wait);
        log.info("Clicking element: {}", ei);
        element.click();
    }
}
