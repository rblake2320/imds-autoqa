package autoqa.player;

import autoqa.model.ElementInfo;
import autoqa.model.RecordedEvent;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code HOVER} events (mouse-over / move-to-element).
 *
 * <p>Strategy:
 * <ol>
 *   <li>Resolve the target element and wait for it to be visible.</li>
 *   <li>Move the mouse to the element using {@link Actions#moveToElement(WebElement)}.</li>
 * </ol>
 */
public class HoverHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(HoverHandler.class);

    @Override
    public void handle(WebDriver driver, RecordedEvent event, LocatorResolver resolver, WaitStrategy wait) {
        ElementInfo ei = HandlerSupport.requireElement(event, "HOVER");
        WebElement element = HandlerSupport.resolveVisible(driver, ei, resolver, wait);
        log.info("Hovering over element: {}", ei);
        new Actions(driver).moveToElement(element).perform();
    }
}
