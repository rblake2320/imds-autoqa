package autoqa.player;

import autoqa.model.CheckpointData;
import autoqa.model.CheckpointData.CheckpointType;
import autoqa.model.CheckpointData.MatchMode;
import autoqa.model.ElementInfo;
import autoqa.model.InputData;
import autoqa.model.ObjectRepository;
import autoqa.model.RecordedEvent;
import autoqa.model.RecordedEvent.EventType;
import autoqa.model.RecordedSession;
import autoqa.model.SelectedOption;
import autoqa.model.TestObject;

import autoqa.ai.AIConfig;
import autoqa.ai.HealingInterceptor;
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
     * Optional shared Object Repository.  When non-null, events with
     * {@code objectName} set are resolved from the OR first.
     */
    private ObjectRepository objectRepository;

    /**
     * Optional AI self-healing interceptor.  When non-null, element lookups are
     * routed through the LLM-backed healing cascade on {@link NoSuchElementException}.
     * Wired when {@code player.healing.enabled=true} and an {@link AIConfig} is provided.
     */
    private final HealingInterceptor healingInterceptor;

    /**
     * Optional screen recorder.  When non-null, a screenshot is taken after
     * each step — equivalent to UFT One's "Screen Recorder" run setting.
     */
    private ScreenRecorder screenRecorder;

    /**
     * All window handles seen and intentionally switched to during this
     * playback run. Used by {@link #handleWindowSwitch} to detect truly new
     * windows without relying on undefined HashSet iteration order.
     */
    private final Set<String> allKnownHandles;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * Creates a fully initialised engine using default component wiring.
     * AI self-healing is disabled; use {@link #PlayerEngine(WebDriver, AIConfig)}
     * to enable the healing cascade.
     *
     * @param driver a configured, ready-to-use WebDriver session
     */
    public PlayerEngine(WebDriver driver) {
        this(driver, (AIConfig) null);
    }

    /**
     * Creates a fully initialised engine with optional AI self-healing.
     *
     * <p>When {@code aiConfig} is non-null, {@code ai.enabled=true}, and
     * {@code player.healing.enabled=true} in config.properties, a
     * {@link HealingInterceptor} is wired around the {@link LocatorResolver}
     * so that element-not-found errors trigger the LLM healing cascade before
     * raising an exception.
     *
     * @param driver   a configured, ready-to-use WebDriver session
     * @param aiConfig AI configuration, or {@code null} to disable healing
     */
    public PlayerEngine(WebDriver driver, AIConfig aiConfig) {
        this.driver            = driver;
        this.config            = new PlayerConfig();
        this.wait              = new WaitStrategy(driver, config.getExplicitWaitSec());
        this.resolver          = new LocatorResolver(driver, wait, config.getLocatorFallbackAttempts());
        this.frameNav          = new FrameNavigator(driver);
        this.sentinel          = new PopupSentinel(driver);
        this.evidenceCollector = new EvidenceCollector(config.getEvidenceDir());
        this.allKnownHandles   = new HashSet<>(driver.getWindowHandles());

        // Wire AI healing chain when both config and AIConfig allow it
        if (aiConfig != null && aiConfig.isAiEnabled() && config.isHealingEnabled()) {
            autoqa.ai.LocatorHealer healer = aiConfig.createLocatorHealer();
            this.healingInterceptor = new HealingInterceptor(resolver, healer, driver);
            log.info("AI self-healing enabled for this playback session");
        } else {
            this.healingInterceptor = null;
        }
    }

    /**
     * Package-private constructor for unit tests — accepts pre-built collaborators
     * so Mockito mocks can be injected without starting a real browser.
     *
     * <p>The healing interceptor is always {@code null} in this path; AI healing
     * is tested separately via the {@link #PlayerEngine(WebDriver, AIConfig)} path.
     */
    PlayerEngine(WebDriver driver, WaitStrategy wait, LocatorResolver resolver,
                 FrameNavigator frameNav, PopupSentinel sentinel,
                 EvidenceCollector evidenceCollector) {
        this.driver            = driver;
        this.config            = new PlayerConfig(new java.util.Properties());
        this.wait              = wait;
        this.resolver          = resolver;
        this.frameNav          = frameNav;
        this.sentinel          = sentinel;
        this.evidenceCollector = evidenceCollector;
        this.allKnownHandles   = new HashSet<>(driver.getWindowHandles());
        this.healingInterceptor = null;
    }

    /** Attaches a shared Object Repository used to resolve named test objects. */
    public void setObjectRepository(ObjectRepository or) {
        this.objectRepository = or;
    }

    /**
     * Attaches a {@link ScreenRecorder}.  Call before {@link #play} to enable
     * per-step screenshot capture (UFT "Screen Recorder" equivalent).
     * The caller is responsible for starting and stopping the recorder.
     */
    public void setScreenRecorder(ScreenRecorder recorder) {
        this.screenRecorder = recorder;
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

        // Ensure we're on the right page before step 1 runs.
        // If the recording has no leading NAVIGATE event (the common case when the
        // user just starts recording on an already-open page), drive the browser to
        // the URL of the first event so elements can actually be found.
        autoNavigateIfNeeded(events);

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

                // 3. Resolve named OR object if present
                resolveObjectName(event);

                // 4. Dispatch
                dispatch(event);

                // 5. Exit frame
                if (enteredFrame) {
                    frameNav.exitFrames();
                    enteredFrame = false;
                }

                // 5b. Screen recording — one frame per step
                if (screenRecorder != null) {
                    String label = event.getComment() != null
                            ? event.getComment()
                            : event.getEventType().toString();
                    screenRecorder.captureStep(i, label);
                }

                // 6. Step pacing
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
            case CHECKPOINT    -> handleCheckpoint(event);
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
        if (event.hasElement()) {
            ElementInfo ei = event.getElement();
            try {
                // Primary: DOM locator (reliable, cross-browser)
                By by = toBy(resolver.resolve(ei));
                WebElement el = wait.waitForClickable(by);
                log.debug("Clicking element: {}", ei);
                el.click();
                return;
            } catch (AutoQAException | org.openqa.selenium.NoSuchElementException locEx) {
                log.warn("DOM locator failed for CLICK — attempting coordinate fallback: {}",
                        locEx.getMessage());
            }
        }

        // Fallback: coordinate-based click (analog mode, like UFT low-level recording)
        // Uses elementFromPoint so the real element receives the click event.
        if (event.getCoordinates() != null) {
            double x = event.getCoordinates().getX();
            double y = event.getCoordinates().getY();
            log.info("Coordinate fallback: clicking at ({}, {})", x, y);
            ((JavascriptExecutor) driver).executeScript(
                    "var el = document.elementFromPoint(arguments[0], arguments[1]);" +
                    "if (el) el.click(); else window.dispatchEvent(new MouseEvent('click'," +
                    "{clientX: arguments[0], clientY: arguments[1], bubbles: true}));", x, y);
        } else {
            throw new AutoQAException("CLICK event has no element and no coordinates — cannot click");
        }
    }

    private void handleDoubleClick(RecordedEvent event) {
        ElementInfo ei = requireElement(event, EventType.DOUBLE_CLICK);
        WebElement el = findElement(ei);
        log.debug("Double-clicking element: {}", ei);
        new Actions(driver).doubleClick(el).perform();
    }

    private void handleContextMenu(RecordedEvent event) {
        ElementInfo ei = requireElement(event, EventType.CONTEXT_MENU);
        WebElement el = findElement(ei);
        log.debug("Context-clicking element: {}", ei);
        new Actions(driver).contextClick(el).perform();
    }

    private void handleInput(RecordedEvent event) {
        ElementInfo ei = requireElement(event, EventType.INPUT);
        InputData inputData = requireInputData(event, EventType.INPUT);
        WebElement el = findElement(ei);
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
            WebElement el = findElement(event.getElement());
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

        WebElement el = findElement(ei);
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
            WebElement el = findElement(ei);
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
        WebElement el = findElement(ei);
        log.debug("Hovering over element: {}", ei);
        new Actions(driver).moveToElement(el).perform();
    }

    private void handleWait(RecordedEvent event) {
        // WAIT events are recorded pacing markers; honour step delay instead
        log.debug("WAIT event encountered — relying on configured step delay");
    }

    // ── Object Repository resolution ──────────────────────────────────────

    /**
     * If the event carries an {@code objectName} and a shared OR is attached,
     * look up the named {@link TestObject} and copy its locators into the
     * event's {@code element} field (only when no inline element was recorded).
     *
     * <p>This mirrors UFT's OR resolution: logical name → locator chain.
     */
    private void resolveObjectName(RecordedEvent event) {
        if (objectRepository == null || !event.hasObjectName()) return;
        TestObject obj = objectRepository.find(event.getObjectName());
        if (obj == null) {
            log.warn("Object Repository lookup: '{}' not found — using inline locators",
                    event.getObjectName());
            return;
        }
        if (event.getElement() == null) {
            log.debug("OR resolved '{}' → {}", event.getObjectName(), obj);
            event.setElement(obj.toElementInfo());
        }
    }

    // ── Checkpoint handler ────────────────────────────────────────────────

    /**
     * Executes a CHECKPOINT event — the IMDS AutoQA equivalent of UFT One's
     * text / element / image / attribute checkpoints.
     *
     * <p>A failing checkpoint throws {@link AutoQAException}, which the main
     * playback loop catches and reports as a test failure (same behaviour as a
     * UFT checkpoint failure stopping the test).
     */
    private void handleCheckpoint(RecordedEvent event) {
        CheckpointData cp = event.getCheckpointData();
        if (cp == null) {
            log.warn("CHECKPOINT event has no checkpointData — skipping");
            return;
        }

        String label = cp.getCheckpointName() != null
                ? cp.getCheckpointName()
                : cp.getCheckpointType().toString();
        log.info("Running checkpoint '{}' (type={})", label, cp.getCheckpointType());

        switch (cp.getCheckpointType()) {
            case TEXT           -> cpText(event, cp);
            case ELEMENT_EXISTS -> cpElementExists(event);
            case URL            -> cpUrl(cp);
            case TITLE          -> cpTitle(cp);
            case ATTRIBUTE      -> cpAttribute(event, cp);
            case SCREENSHOT     -> cpScreenshot(cp);
            default             -> throw new AutoQAException(
                    "Unknown checkpoint type: " + cp.getCheckpointType());
        }
    }

    private void cpText(RecordedEvent event, CheckpointData cp) {
        ElementInfo ei = requireElement(event, EventType.CHECKPOINT);
        WebElement el  = findElement(ei);
        String actual  = el.getText();
        assertMatch("TEXT", actual, cp);
    }

    private void cpElementExists(RecordedEvent event) {
        ElementInfo ei = requireElement(event, EventType.CHECKPOINT);
        try {
            findElement(ei);
            log.info("Checkpoint ELEMENT_EXISTS: element found ✓");
        } catch (Exception e) {
            throw new AutoQAException(
                    "Checkpoint ELEMENT_EXISTS failed — element not found: " + ei, e);
        }
    }

    private void cpUrl(CheckpointData cp) {
        String actual = driver.getCurrentUrl();
        assertMatch("URL", actual, cp);
    }

    private void cpTitle(CheckpointData cp) {
        String actual = driver.getTitle();
        assertMatch("TITLE", actual, cp);
    }

    private void cpAttribute(RecordedEvent event, CheckpointData cp) {
        String attrName = cp.getAttributeName();
        if (attrName == null || attrName.isBlank()) {
            throw new AutoQAException("ATTRIBUTE checkpoint has no attributeName");
        }
        ElementInfo ei = requireElement(event, EventType.CHECKPOINT);
        WebElement  el = findElement(ei);
        String actual  = el.getAttribute(attrName);
        assertMatch("ATTRIBUTE[" + attrName + "]", actual, cp);
    }

    /**
     * Pixel-diff screenshot checkpoint — equivalent to UFT One's bitmap/image
     * checkpoint.  Captures the current viewport, compares it to the baseline
     * PNG pixel-by-pixel, and fails if the diff ratio exceeds the threshold.
     */
    private void cpScreenshot(CheckpointData cp) {
        String baselinePath = cp.getBaselineImagePath();
        if (baselinePath == null || baselinePath.isBlank()) {
            throw new AutoQAException("SCREENSHOT checkpoint has no baselineImagePath");
        }

        java.io.File shot = ((org.openqa.selenium.TakesScreenshot) driver)
                .getScreenshotAs(org.openqa.selenium.OutputType.FILE);
        try {
            java.awt.image.BufferedImage actual   = javax.imageio.ImageIO.read(shot);
            java.awt.image.BufferedImage baseline = javax.imageio.ImageIO.read(new java.io.File(baselinePath));

            if (actual == null || baseline == null) {
                throw new AutoQAException("SCREENSHOT checkpoint: could not read one or both images");
            }

            int  w          = Math.min(actual.getWidth(),  baseline.getWidth());
            int  h          = Math.min(actual.getHeight(), baseline.getHeight());
            long diffPixels = 0;
            long total      = (long) w * h;

            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    if (actual.getRGB(x, y) != baseline.getRGB(x, y)) diffPixels++;
                }
            }

            double ratio     = total > 0 ? (double) diffPixels / total : 0.0;
            double threshold = cp.getScreenshotThreshold();

            if (ratio > threshold) {
                throw new AutoQAException(String.format(
                        "Checkpoint SCREENSHOT failed: %.2f%% pixels differ (threshold %.2f%%)",
                        ratio * 100, threshold * 100));
            }
            log.info("Checkpoint SCREENSHOT: {}% pixel diff ≤ threshold {}% ✓",
                    String.format("%.2f", ratio * 100),
                    String.format("%.2f", threshold * 100));

        } catch (java.io.IOException e) {
            throw new AutoQAException("SCREENSHOT checkpoint: I/O error reading images", e);
        }
    }

    /**
     * Asserts that {@code actual} matches {@code expected} according to the
     * {@link MatchMode} and {@code caseSensitive} flag in {@code cp}.
     *
     * @throws AutoQAException with a descriptive message when the assertion fails
     */
    private void assertMatch(String label, String actual, CheckpointData cp) {
        String expected = cp.getExpectedValue();
        if (expected == null) {
            log.warn("Checkpoint {} has no expectedValue — passing trivially", label);
            return;
        }

        // Apply case-folding before comparison
        String a = cp.isCaseSensitive() ? actual   : (actual   != null ? actual.toLowerCase()   : null);
        String e = cp.isCaseSensitive() ? expected : expected.toLowerCase();

        boolean match = switch (cp.getMatchMode()) {
            case EQUALS      -> e.equals(a);
            case CONTAINS    -> a != null && a.contains(e);
            case STARTS_WITH -> a != null && a.startsWith(e);
            case REGEX       -> actual != null && actual.matches(
                    cp.isCaseSensitive() ? expected : "(?i)" + expected);
        };

        if (!match) {
            throw new AutoQAException(String.format(
                    "Checkpoint %s failed — expected [%s] (%s) but got [%s]",
                    label, expected, cp.getMatchMode(), actual));
        }
        log.info("Checkpoint {}: '{}' {} '{}' ✓", label, actual, cp.getMatchMode(), expected);
    }

    // ── Auto-navigation ───────────────────────────────────────────────────

    /**
     * If the recording does not begin with a {@code NAVIGATE} event, drives the
     * browser to the URL of the first event that has one.  This handles the
     * common case where the user started recording after the page was already
     * open, so no navigation event was captured.
     *
     * <p>Without this, the player would start on {@code about:blank} and every
     * element look-up would fail immediately.
     */
    private void autoNavigateIfNeeded(List<RecordedEvent> events) {
        if (events.isEmpty()) return;

        // If the recording already starts with a NAVIGATE, nothing to do.
        if (events.get(0).getEventType() == EventType.NAVIGATE) return;

        // Find the URL carried by the earliest event.
        String startUrl = events.stream()
                .map(RecordedEvent::getUrl)
                .filter(u -> u != null && !u.isBlank()
                             && !u.equalsIgnoreCase("about:blank")
                             && !u.equalsIgnoreCase("about:newtab"))
                .findFirst()
                .orElse(null);

        if (startUrl != null) {
            log.info("Auto-navigating to recording start URL: {}", startUrl);
            driver.get(startUrl);
            wait.waitForPageLoad();
        }
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
     * Finds the DOM element described by {@code ei}, routing through the
     * {@link HealingInterceptor} when AI self-healing is enabled, or falling
     * back to the bare {@link LocatorResolver} otherwise.
     *
     * <p>All handler methods that need an element should use this helper
     * rather than calling {@code resolver.findElement()} directly.
     */
    private WebElement findElement(ElementInfo ei) {
        return healingInterceptor != null
                ? healingInterceptor.findElement(ei)
                : resolver.findElement(ei);
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
