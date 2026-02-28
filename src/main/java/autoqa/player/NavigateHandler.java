package autoqa.player;

import autoqa.model.RecordedEvent;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code NAVIGATE} events (direct URL navigation via {@link WebDriver#get(String)}).
 *
 * <p>Strategy:
 * <ol>
 *   <li>Validate that a URL is present in the event.</li>
 *   <li>Call {@link WebDriver#get(String)} with the recorded URL.</li>
 *   <li>Wait for {@code document.readyState == 'complete'} via
 *       {@link WaitStrategy#waitForPageLoad()}.</li>
 * </ol>
 */
public class NavigateHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(NavigateHandler.class);

    @Override
    public void handle(WebDriver driver, RecordedEvent event, LocatorResolver resolver, WaitStrategy wait) {
        String url = event.getUrl();
        if (url == null || url.isBlank()) {
            throw new AutoQAException("NAVIGATE event has no URL: " + event);
        }

        log.info("Navigating to URL: {}", url);
        driver.get(url);
        wait.waitForPageLoad();
        log.info("Page load complete after navigating to: {}", url);
    }
}
