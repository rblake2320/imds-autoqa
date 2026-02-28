package autoqa.player;

import autoqa.model.Coordinates;
import autoqa.model.ElementInfo;
import autoqa.model.ElementLocator;
import autoqa.model.RecordedEvent;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code SCROLL} events.
 *
 * <p>Two modes:
 * <ul>
 *   <li><strong>Element scroll</strong> — if the event has an element, scroll it smoothly
 *       into the centre of the viewport using {@code scrollIntoView}.</li>
 *   <li><strong>Coordinate scroll</strong> — if no element but coordinates are present,
 *       call {@code window.scrollTo(x, y)}.</li>
 * </ul>
 */
public class ScrollHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(ScrollHandler.class);

    @Override
    public void handle(WebDriver driver, RecordedEvent event, LocatorResolver resolver, WaitStrategy wait) {
        JavascriptExecutor js = (JavascriptExecutor) driver;

        if (event.hasElement()) {
            ElementInfo ei = event.getElement();
            // Use presence check — element does not need to be visible to scroll to it
            ElementLocator locator = resolver.resolve(ei);
            By by = HandlerSupport.toBy(locator);
            WebElement element = wait.waitForPresent(by);
            log.info("Scrolling element into view: {}", ei);
            js.executeScript(
                    "arguments[0].scrollIntoView({behavior: 'smooth', block: 'center', inline: 'nearest'});",
                    element);
        } else {
            Coordinates coords = event.getCoordinates();
            if (coords != null && coords.getX() != null && coords.getY() != null) {
                double x = coords.getX();
                double y = coords.getY();
                log.info("Scrolling to coordinates ({}, {})", x, y);
                js.executeScript("window.scrollTo(arguments[0], arguments[1]);", x, y);
            } else {
                log.warn("SCROLL event has neither element nor coordinates — nothing to scroll to: {}", event);
            }
        }
    }
}
