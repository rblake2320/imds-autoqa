package autoqa.player;

import autoqa.model.ElementInfo;
import autoqa.model.InputData;
import autoqa.model.RecordedEvent;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles {@code INPUT} events (typing into form fields).
 *
 * <p>Strategy:
 * <ol>
 *   <li>Resolve the target element and wait for it to be visible.</li>
 *   <li>Clear the existing value, then send the recorded keys.</li>
 *   <li>Log {@code [REDACTED]} instead of the actual value for password fields.</li>
 * </ol>
 */
public class InputHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(InputHandler.class);

    @Override
    public void handle(WebDriver driver, RecordedEvent event, LocatorResolver resolver, WaitStrategy wait) {
        ElementInfo ei = HandlerSupport.requireElement(event, "INPUT");

        InputData inputData = event.getInputData();
        if (inputData == null) {
            throw new AutoQAException("INPUT event has no inputData: " + event);
        }
        String keys = inputData.getKeys();
        if (keys == null) {
            throw new AutoQAException("INPUT event inputData.keys is null: " + event);
        }

        WebElement element = HandlerSupport.resolveVisible(driver, ei, resolver, wait);

        boolean isPassword = isPasswordField(ei);
        String logValue = isPassword ? "[REDACTED]" : keys;

        log.info("Typing into element {} — value: {}", ei, logValue);
        element.clear();
        element.sendKeys(keys);
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Returns {@code true} if the element info indicates a password input.
     * Checks the {@code type} attribute and falls back to the {@code attributes} map.
     */
    private boolean isPasswordField(ElementInfo info) {
        if ("password".equalsIgnoreCase(info.getType())) {
            return true;
        }
        if (info.getAttributes() != null) {
            return "password".equalsIgnoreCase(info.getAttributes().get("type"));
        }
        return false;
    }
}
