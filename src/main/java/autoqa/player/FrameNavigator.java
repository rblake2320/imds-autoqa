package autoqa.player;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Navigates the browser's frame/iframe hierarchy before element interaction
 * and resets back to the top-level document afterwards.
 *
 * <p>Frame locators in the recorded chain may be:
 * <ul>
 *   <li>A numeric string like {@code "0"}, {@code "1"} — treated as a
 *       zero-based frame index.</li>
 *   <li>Any other string — treated as a CSS selector or name attribute,
 *       resolved via {@code By.cssSelector()}.</li>
 * </ul>
 */
public class FrameNavigator {

    private static final Logger log = LoggerFactory.getLogger(FrameNavigator.class);

    private final WebDriver driver;

    /**
     * @param driver active WebDriver session
     */
    public FrameNavigator(WebDriver driver) {
        this.driver = driver;
    }

    /**
     * Switches to the top-level document first, then descends through each
     * frame locator in {@code frameChain} in order.
     *
     * @param frameChain ordered list of frame locators; may be empty or null
     *                   (no-op in that case)
     * @throws AutoQAException if any frame in the chain cannot be entered
     */
    public void enterFrames(List<String> frameChain) {
        if (frameChain == null || frameChain.isEmpty()) {
            log.debug("No frame chain — staying in top-level document");
            return;
        }

        log.debug("Entering frame chain of depth {}: {}", frameChain.size(), frameChain);

        // Always start from the root to avoid nesting errors
        driver.switchTo().defaultContent();

        for (String locator : frameChain) {
            log.debug("Switching to frame: '{}'", locator);
            try {
                if (isNumeric(locator)) {
                    int index = Integer.parseInt(locator);
                    driver.switchTo().frame(index);
                } else {
                    // Try as a CSS selector / name
                    driver.switchTo().frame(driver.findElement(By.cssSelector(locator)));
                }
            } catch (Exception e) {
                throw new AutoQAException(
                        "Failed to switch to frame '" + locator + "' in chain " + frameChain, e);
            }
        }

        log.debug("Successfully entered {} frame(s)", frameChain.size());
    }

    /**
     * Returns focus to the top-level document, discarding any frame context.
     */
    public void exitFrames() {
        log.debug("Exiting frames — switching to defaultContent");
        driver.switchTo().defaultContent();
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code value} represents a non-negative integer
     * (i.e., a frame index).
     */
    private static boolean isNumeric(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
