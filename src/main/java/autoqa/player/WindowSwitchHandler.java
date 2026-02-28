package autoqa.player;

import autoqa.model.RecordedEvent;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Handles {@code WINDOW_SWITCH} events.
 *
 * <p>Strategy:
 * <ul>
 *   <li>If {@code event.getWindowHandle()} is non-null and the handle is still open:
 *       switch to it directly.</li>
 *   <li>Otherwise: switch to the newest window — the non-current handle that appears
 *       last in iteration order (most recently opened in most drivers).</li>
 * </ul>
 */
public class WindowSwitchHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(WindowSwitchHandler.class);

    @Override
    public void handle(WebDriver driver, RecordedEvent event, LocatorResolver resolver, WaitStrategy wait) {
        String currentHandle = currentHandleSafe(driver);
        Set<String> allHandles = driver.getWindowHandles();
        String recordedHandle = event.getWindowHandle();

        log.info("WINDOW_SWITCH: current='{}', all={}, recorded='{}'",
                currentHandle, allHandles, recordedHandle);

        if (recordedHandle != null && !recordedHandle.isBlank() && allHandles.contains(recordedHandle)) {
            log.info("Switching to recorded handle: '{}'", recordedHandle);
            driver.switchTo().window(recordedHandle);
        } else {
            String newest = findNewestHandle(allHandles, currentHandle);
            if (newest == null) {
                throw new AutoQAException(
                        "WINDOW_SWITCH: cannot determine target window. "
                        + "Only one window is open and recorded handle '"
                        + recordedHandle + "' is unavailable.");
            }
            log.info("Switching to newest window handle: '{}'", newest);
            driver.switchTo().window(newest);
        }

        String newHandle = currentHandleSafe(driver);
        log.info("WINDOW_SWITCH complete: was='{}', now='{}'", currentHandle, newHandle);
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Returns the last non-current handle in iteration order.
     * In Chrome/Edge this corresponds to the most recently opened window/tab.
     */
    private String findNewestHandle(Set<String> allHandles, String currentHandle) {
        String candidate = null;
        for (String h : allHandles) {
            if (!h.equals(currentHandle)) {
                candidate = h; // keep going — last non-current handle wins
            }
        }
        return candidate;
    }

    private String currentHandleSafe(WebDriver driver) {
        try {
            return driver.getWindowHandle();
        } catch (Exception e) {
            return "(unknown)";
        }
    }
}
