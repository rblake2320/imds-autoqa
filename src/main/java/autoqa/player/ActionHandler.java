package autoqa.player;

import autoqa.model.RecordedEvent;
import org.openqa.selenium.WebDriver;

/**
 * Strategy interface implemented by every event-type handler.
 *
 * <p>Handlers are stateless; all context (driver, resolver, wait) is supplied
 * per-call so that the same handler instance can be reused across steps.
 */
public interface ActionHandler {

    /**
     * Executes the action described by {@code event}.
     *
     * @param driver   the live WebDriver session
     * @param event    the recorded event to replay
     * @param resolver resolves {@link autoqa.model.ElementInfo} to a live {@link org.openqa.selenium.WebElement}
     * @param wait     fluent wait helpers (clickable, presence, page-load, alert, …)
     * @throws AutoQAException if the action cannot be completed
     */
    void handle(WebDriver driver, RecordedEvent event, LocatorResolver resolver, WaitStrategy wait);
}
