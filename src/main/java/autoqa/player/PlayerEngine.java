package autoqa.player;

import autoqa.model.ElementInfo;
import autoqa.model.InputData;
import autoqa.model.RecordedEvent;
import autoqa.model.RecordedEvent.EventType;
import autoqa.model.RecordedSession;
import autoqa.model.SelectedOption;

import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.Select;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Main orchestrator that replays a {@link RecordedSession} step by step.
 *
 * <p>For each event the engine:
 * <ol>
 *   <li>Checks for unexpected popup windows via {@link PopupSentinel}.</li>
 *   <li>Enters the required frame chain if the element is inside a frame.</li>
 *   <li>Dispatches to the appropriate handler based on {@link EventType}.</li>
 *   <li>Exits frames after the interaction.</li>
 *   <li>Sleeps {@code config.getStepDelayMs()} for pacing.</li>
 * </ol>
 *
 * <p>On any unrecoverable exception the engine collects evidence via
 * {@link EvidenceCollector} and re-throws an {@link AutoQAException}.
 */
public class PlayerEngine {

    private static final Logger log = LoggerFactory.getLogger(PlayerEngine.class);

    private final WebDriver driver;
    private final PlayerConfig config;
    private final WaitStrategy wait;
    private final LocatorResolver resolver;
    private final FrameNavigator frameNav;
    private final PopupSentinel sentinel;
    private final EvidenceCollector evidenceCollector;
    /**
     * All window handles seen and intentionally switched to during this
     * playback run. Used by {@link #handleWindowSwitch} to detect truly new
     * windows without relying on undefined HashSet iteration order.
     */
    private final Set<String> allKnownHandles;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * Creates a fully initialised engine using default component wiring.
     *
     * @param driver a configured, ready-to-use WebDriver session
     */
    public PlayerEngine(WebDriver driver) {
        this.driver            = driver;
        this.config            = new PlayerConfig();
        this.wait              = new WaitStrategy(driver, config.getExplicitWaitSec());
        this.resolver          = new LocatorResolver(driver, wait, config.getLocatorFallbackAttempts());
        this.frameNav          = new FrameNavigator(driver);
        this.sentinel          = new PopupSentinel(driver);
        this.evidenceCollector = new EvidenceCollector(config.getEvidenceDir());
        this.allKnownHandles   = new HashSet<>(driver.getWindowHandles());
    }

    // ── Playback ──────────────────────────────────────────────────────────

    /**
     * Replays every event in the supplied session in recorded order.
     *
     * @param session the session to replay
     * @return a {@link PlaybackResult} summarising success/failure
     */
    public PlaybackResult play(RecordedSession session) {
        List<RecordedEvent> events = session.getEvents();
        String sessionId = session.getSessionId();
        int total = events.size();

        log.info("Starting playback of session '{}' — {} step(s)", sessionId, total);

        for (int i = 0; i < total; i++) {
            RecordedEvent event = events.get(i);
            log.info("Step {}/{}: {} — {}", i + 1, total, event.getEventType(),
                    event.getComment() != null ? event.getComment() : event.getUrl());

            boolean enteredFrame = false;
            try {
                // 1. Popup guard
                sentinel.check();

                // 2. Frame context
                if (event.isInFrame()) {
                    frameNav.enterFrames(event.getFrameChain());
                    enteredFrame = true;
                }

                // 3. Dispatch
                dispatch(event);

                // 4. Exit frame
                if (enteredFrame) {
                    frameNav.exitFrames();
                    enteredFrame = false;
                }

                // 5. Step pacing
                long delay = config.getStepDelayMs();
                if (delay > 0) {
                    Thread.sleep(delay);
                }

            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                String reason = "Playback interrupted at step " + (i + 1);
                log.error(reason, ie);
                if (enteredFrame) frameNav.exitFrames();
                evidenceCollector.collect(driver, sessionId, i, event);
                return new PlaybackResult(false, i, total, reason);

            } catch (AutoQAException aqe) {
                String reason = "AutoQA failure at step " + (i + 1) + ": " + aqe.getMessage();
                log.error(reason, aqe);
                if (enteredFrame) frameNav.exitFrames();
                evidenceCollector.collect(driver, sessionId, i, event);
                return new PlaybackResult(false, i, total, reason);

            } catch (Exception e) {
                String reason = "Unexpected error at step " + (i + 1) + ": " + e.getMessage();
                log.error(reason, e);
                if (enteredFrame) frameNav.exitFrames();
                evidenceCollector.collect(driver, sessionId, i, event);
                return new PlaybackResult(false, i, total, reason);
            }
        }

        log.info("Playback of session '{}' completed successfully ({} steps)", sessionId, total);
        return new PlaybackResult(true, total, total, null);
    }

    // ── Event dispatch ────────────────────────────────────────────────────

    private void dispatch(RecordedEvent event) {
        EventType type = event.getEventType();
        if (type == null) {
            throw new AutoQAException("Event has null eventType: " + event);
        }

        switch (type) {
            case NAVIGATE      -> handleNavigate(event);
            case CLICK         -> handleClick(event);
            case DOUBLE_CLICK  -> handleDoubleClick(event);
            case CONTEXT_MENU  -> handleContextMenu(event);
            case INPUT         -> handleInput(event);
            case KEY_PRESS     -> handleKeyPress(event);
            case SELECT        -> handleSelect(event);
            case SCROLL        -> handleScroll(event);
            case ALERT         -> handleAlert(event);
            case WINDOW_SWITCH -> handleWindowSwitch(event);
            case HOVER         -> handleHover(event);
            case FRAME_SWITCH  -> { /* Frame switching is handled via frameChain — no-op here */ }
            case DRAG_DROP     -> log.warn("DRAG_DROP not yet implemented — skipping step");
            case WAIT          -> handleWait(event);
            default            -> throw new AutoQAException("Unsupported event type: " + type);
        }
    }

    // ── Handlers ──────────────────────────────────────────────────────────

    private void handleNavigate(RecordedEvent event) {
        String url = event.getUrl();
        if (url == null || url.isBlank()) {
            throw new AutoQAException("NAVIGATE event has no URL");
        }
        log.debug("Navigating to: {}", url);
        driver.get(url);
        wait.waitForPageLoad();
    }

    private void handleClick(RecordedEvent event) {
        ElementInfo ei = requireElement(event, EventType.CLICK);
        // Resolve the locator first, then wait-for-clickable which returns a fresh
        // element reference — avoids the stale-element race of findElement→wait→click.
        By by = toBy(resolver.resolve(ei));
        WebElement el = wait.waitForClickable(by);
        log.debug("Clicking element: {}", ei);
        el.click();
    }

    private void handleDoubleClick(RecordedEvent event) {
        ElementInfo ei = requireElement(event, EventType.DOUBLE_CLICK);
        WebElement el = resolver.findElement(ei);
        log.debug("Double-clicking element: {}", ei);
        new Actions(driver).doubleClick(el).perform();
    }

    private void handleContextMenu(RecordedEvent event) {
        ElementInfo ei = requireElement(event, EventType.CONTEXT_MENU);
        WebElement el = resolver.findElement(ei);
        log.debug("Context-clicking element: {}", ei);
        new Actions(driver).contextClick(el).perform();
    }

    private void handleInput(RecordedEvent event) {
        ElementInfo ei = requireElement(event, EventType.INPUT);
        InputData inputData = requireInputData(event, EventType.INPUT);
        WebElement el = resolver.findElement(ei);
        String keys = inputData.getKeys() != null ? inputData.getKeys() : "";
        log.debug("Typing '{}' into: {}", keys, ei);
        el.clear();
        el.sendKeys(keys);
    }

    private void handleKeyPress(RecordedEvent event) {
        InputData inputData = requireInputData(event, EventType.KEY_PRESS);
        String keyCode = inputData.getKeyCode();
        if (keyCode == null || keyCode.isBlank()) {
            throw new AutoQAException("KEY_PRESS event has no keyCode");
        }

        Keys key;
        try {
            key = Keys.valueOf(keyCode.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new AutoQAException("Unknown Keys constant: '" + keyCode + "'", e);
        }

        if (event.hasElement()) {
            WebElement el = resolver.findElement(event.getElement());
            log.debug("Sending key {} to element: {}", keyCode, event.getElement());
            el.sendKeys(key);
        } else {
            log.debug("Sending key {} to page body", keyCode);
            new Actions(driver).sendKeys(key).perform();
        }
    }

    private void handleSelect(RecordedEvent event) {
        ElementInfo ei = requireElement(event, EventType.SELECT);
        InputData inputData = requireInputData(event, EventType.SELECT);
        SelectedOption option = inputData.getSelectedOption();
        if (option == null) {
            throw new AutoQAException("SELECT event has no selectedOption in inputData");
        }

        WebElement el = resolver.findElement(ei);
        Select select = new Select(el);

        if (option.getText() != null && !option.getText().isBlank()) {
            log.debug("Selecting by visible text: '{}'", option.getText());
            select.selectByVisibleText(option.getText());
        } else if (option.getValue() != null && !option.getValue().isBlank()) {
            log.debug("Selecting by value: '{}'", option.getValue());
            select.selectByValue(option.getValue());
        } else if (option.getIndex() != null) {
            log.debug("Selecting by index: {}", option.getIndex());
            select.selectByIndex(option.getIndex());
        } else {
            throw new AutoQAException("SELECT event selectedOption has no text, value, or index");
        }
    }

    private void handleScroll(RecordedEvent event) {
        if (event.hasElement()) {
            // Element-targeted scroll: scroll the specific element into view.
            ElementInfo ei = event.getElement();
            WebElement el = resolver.findElement(ei);
            log.debug("Scrolling element into view: {}", ei);
            ((JavascriptExecutor) driver).executeScript("arguments[0].scrollIntoView(true);", el);
        } else {
            // Coordinate-based scroll: scroll the viewport to (x, y) screen coordinates.
            double x = event.getCoordinates() != null ? event.getCoordinates().getX() : 0.0;
            double y = event.getCoordinates() != null ? event.getCoordinates().getY() : 0.0;
            log.debug("Scrolling to coordinates: ({}, {})", x, y);
            ((JavascriptExecutor) driver).executeScript(
                    "window.scrollTo(arguments[0], arguments[1]);", x, y);
        }
    }

    private void handleAlert(RecordedEvent event) {
        InputData inputData = event.getInputData();
        InputData.AlertAction action = inputData != null
                ? inputData.getAlertAction()
                : InputData.AlertAction.ACCEPT;

        if (action == null) action = InputData.AlertAction.ACCEPT;

        log.debug("Handling alert with action: {}", action);
        Alert alert = wait.waitForAlertPresent();

        switch (action) {
            case ACCEPT -> alert.accept();
            case DISMISS -> alert.dismiss();
            case SEND_KEYS -> {
                String text = inputData.getAlertText();
                if (text == null) text = "";
                log.debug("Sending text to prompt: '{}'", text);
                alert.sendKeys(text);
                alert.accept();
            }
        }
    }

    private void handleWindowSwitch(RecordedEvent event) {
        String targetHandle = event.getWindowHandle();
        if (targetHandle == null || targetHandle.isBlank()) {
            // Find the handle that is NOT in our tracked set. WaitStrategy polls
            // until such a handle appears, then returns it — safe against undefined
            // HashSet ordering and race conditions with the new window appearing.
            String newHandle = wait.waitForNewWindow(allKnownHandles);
            log.debug("Switching to new window handle: {}", newHandle);
            driver.switchTo().window(newHandle);
            allKnownHandles.add(newHandle);
        } else {
            log.debug("Switching to window handle: {}", targetHandle);
            driver.switchTo().window(targetHandle);
            allKnownHandles.add(targetHandle);
        }
        wait.waitForPageLoad();
    }

    private void handleHover(RecordedEvent event) {
        ElementInfo ei = requireElement(event, EventType.HOVER);
        WebElement el = resolver.findElement(ei);
        log.debug("Hovering over element: {}", ei);
        new Actions(driver).moveToElement(el).perform();
    }

    private void handleWait(RecordedEvent event) {
        // WAIT events are recorded pacing markers; honour step delay instead
        log.debug("WAIT event encountered — relying on configured step delay");
    }

    // ── Convenience helpers ───────────────────────────────────────────────

    private ElementInfo requireElement(RecordedEvent event, EventType type) {
        if (!event.hasElement()) {
            throw new AutoQAException(type + " event has no element");
        }
        return event.getElement();
    }

    private InputData requireInputData(RecordedEvent event, EventType type) {
        if (!event.hasInputData()) {
            throw new AutoQAException(type + " event has no inputData");
        }
        return event.getInputData();
    }

    /**
     * Converts a resolved {@link autoqa.model.ElementLocator} to a Selenium
     * {@link By} so we can call the typed {@code waitForClickable(By)} variant.
     */
    private By toBy(autoqa.model.ElementLocator locator) {
        return switch (locator.getStrategy()) {
            case ID    -> By.id(locator.getValue());
            case NAME  -> By.name(locator.getValue());
            case CSS   -> By.cssSelector(locator.getValue());
            case XPATH -> By.xpath(locator.getValue());
            default    -> By.xpath(locator.getValue());
        };
    }

    // ═════════════════════════════════════════════════════════════════════
    // PlaybackResult
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Immutable summary of a playback run.
     */
    public static final class PlaybackResult {

        private final boolean success;
        private final int stepsCompleted;
        private final int totalSteps;
        private final String failureReason;

        public PlaybackResult(boolean success, int stepsCompleted,
                              int totalSteps, String failureReason) {
            this.success        = success;
            this.stepsCompleted = stepsCompleted;
            this.totalSteps     = totalSteps;
            this.failureReason  = failureReason;
        }

        /** True when all steps completed without error. */
        public boolean isSuccess()         { return success; }

        /** Number of steps that finished before the run ended (or all on success). */
        public int getStepsCompleted()     { return stepsCompleted; }

        /** Total number of steps in the session. */
        public int getTotalSteps()         { return totalSteps; }

        /** Human-readable failure reason, or {@code null} on success. */
        public String getFailureReason()   { return failureReason; }

        @Override
        public String toString() {
            return success
                    ? String.format("PlaybackResult{SUCCESS, %d/%d steps}", stepsCompleted, totalSteps)
                    : String.format("PlaybackResult{FAILED at step %d/%d: %s}",
                                    stepsCompleted, totalSteps, failureReason);
        }
    }
}
