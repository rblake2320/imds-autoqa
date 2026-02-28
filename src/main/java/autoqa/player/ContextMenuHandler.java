package autoqa.player;

import autoqa.model.ElementInfo;
import autoqa.model.RecordedEvent;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code CONTEXT_MENU} (right-click) events.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Resolve the winning locator and wait for the element to be clickable.</li>
 *   <li>Perform a context-click (right-click) using {@link Actions}.</li>
 * </ol>
 */
public class ContextMenuHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(ContextMenuHandler.class);

    @Override
    public void handle(WebDriver driver, RecordedEvent event, LocatorResolver resolver, WaitStrategy wait) {
        ElementInfo ei = HandlerSupport.requireElement(event, "CONTEXT_MENU");
        WebElement element = HandlerSupport.resolveClickable(driver, ei, resolver, wait);
        log.info("Right-clicking (context menu) element: {}", ei);
        new Actions(driver).contextClick(element).perform();
    }
}
