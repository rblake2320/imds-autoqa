package autoqa.player;

import org.openqa.selenium.By;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * Runs before every player step to detect and handle unexpected popups.
 *
 * <ul>
 *   <li>Browser-native {@code alert / confirm / prompt} — auto-dismissed.</li>
 *   <li>Extra browser windows — logged as a warning; the player must handle intentional
 *       window switches via {@code WINDOW_SWITCH} events.</li>
 *   <li>DOM modal dialogs ({@code .modal:visible}, {@code [role=dialog]}, etc.) —
 *       logged as a warning; intentional dialogs are handled via {@code ALERT} events.</li>
 * </ul>
 */
public class PopupSentinel {

    private static final Logger log = LoggerFactory.getLogger(PopupSentinel.class);

    /** CSS selectors considered "visible modal overlay" indicators. */
    private static final List<By> MODAL_LOCATORS = List.of(
            By.cssSelector("[role='dialog']:not([aria-hidden='true'])"),
            By.cssSelector("[role='alertdialog']"),
            By.cssSelector(".modal.show"),          // Bootstrap 4/5
            By.cssSelector(".modal.in"),             // Bootstrap 3
            By.cssSelector(".ui-dialog:visible"),    // jQuery UI
            By.cssSelector(".mfp-content"),          // Magnific Popup
            By.cssSelector("[data-modal='true']"),
            By.cssSelector(".dialog:not([hidden])")
    );

    private final WebDriver driver;

    /** The number of window handles the player considers "expected" at startup. */
    private int expectedWindowCount;

    public PopupSentinel(WebDriver driver) {
        this.driver = driver;
        this.expectedWindowCount = driver.getWindowHandles().size();
    }

    /**
     * Checks for unexpected popups.
     *
     * @return {@code true} if a native browser alert was found and dismissed;
     *         {@code false} otherwise.
     */
    public boolean check() {
        boolean alertHandled = checkAlert();
        checkNewWindow();
        checkDomModal();
        return alertHandled;
    }

    /**
     * Updates the expected window count — call after a deliberate
     * {@code WINDOW_SWITCH} step opens or closes a window.
     */
    public void updateExpectedWindowCount(int count) {
        this.expectedWindowCount = count;
    }

    // ── Private helpers ──────────────────────────────────────────────────

    /**
     * Attempts to switch to a native alert. If one is present, logs and dismisses it.
     *
     * @return {@code true} if an alert was found and dismissed.
     */
    private boolean checkAlert() {
        try {
            org.openqa.selenium.Alert alert = driver.switchTo().alert();
            String alertText = "(no text)";
            try {
                alertText = alert.getText();
            } catch (Exception ignored) {
                // Some drivers throw if getText() is called after alert disappears
            }
            log.warn("PopupSentinel: unexpected native alert detected — dismissing. Text: '{}'", alertText);
            alert.dismiss();
            return true;
        } catch (NoAlertPresentException e) {
            return false;
        } catch (Exception e) {
            log.debug("PopupSentinel: alert check threw unexpected exception ({}): {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /**
     * Warns if more browser windows are open than expected.
     * Does NOT switch window — the player handles that via WINDOW_SWITCH events.
     */
    private void checkNewWindow() {
        try {
            Set<String> handles = driver.getWindowHandles();
            int current = handles.size();
            if (current > expectedWindowCount) {
                log.warn("PopupSentinel: {} unexpected extra window(s) detected (expected {}, found {}). "
                                + "Use a WINDOW_SWITCH event to handle intentional new windows.",
                        current - expectedWindowCount, expectedWindowCount, current);
            }
        } catch (Exception e) {
            log.debug("PopupSentinel: window-count check failed: {}", e.getMessage());
        }
    }

    /**
     * Checks for visible DOM modal elements and logs a warning if any are found.
     * Does NOT attempt to close them — intentional dialogs are handled by ALERT events.
     *
     * @return always {@code false}; modals are not auto-closed by the sentinel.
     */
    private boolean checkDomModal() {
        for (By locator : MODAL_LOCATORS) {
            try {
                List<WebElement> modals = driver.findElements(locator);
                List<WebElement> visible = modals.stream()
                        .filter(el -> {
                            try {
                                return el.isDisplayed();
                            } catch (Exception ignored) {
                                return false;
                            }
                        })
                        .toList();

                if (!visible.isEmpty()) {
                    log.warn("PopupSentinel: {} visible DOM modal(s) detected matching '{}'. "
                                    + "If intentional, handle via an ALERT or CLICK event in the recording.",
                            visible.size(), locator);
                    // Return early — one warning is enough per check() call
                    return false;
                }
            } catch (Exception e) {
                log.debug("PopupSentinel: DOM modal check for '{}' threw: {}", locator, e.getMessage());
            }
        }
        return false;
    }
}
