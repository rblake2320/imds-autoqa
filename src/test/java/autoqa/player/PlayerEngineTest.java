package autoqa.player;

import autoqa.model.CheckpointData;
import autoqa.model.CheckpointData.CheckpointType;
import autoqa.model.CheckpointData.MatchMode;
import autoqa.model.Coordinates;
import autoqa.model.ElementInfo;
import autoqa.model.ElementLocator;
import autoqa.model.ElementLocator.Strategy;
import autoqa.model.InputData;
import autoqa.model.InputData.AlertAction;
import autoqa.model.ObjectRepository;
import autoqa.model.RecordedEvent;
import autoqa.model.RecordedEvent.EventType;
import autoqa.model.RecordedSession;
import autoqa.model.TestObject;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriver.TargetLocator;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.interactions.Interactive;
import org.openqa.selenium.interactions.Sequence;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PlayerEngine}.
 *
 * <p>All Selenium collaborators are mocked; no real browser is needed.
 * Uses the package-private 6-argument constructor for full mock injection.
 */
public class PlayerEngineTest {

    // ── Mock interfaces ───────────────────────────────────────────────────

    /** Combined interface so Mockito can create a mock satisfying all Selenium needs. */
    private interface FullDriver extends WebDriver, JavascriptExecutor, Interactive {}

    // ── Mocks ─────────────────────────────────────────────────────────────

    @Mock FullDriver driver;
    @Mock WaitStrategy wait;
    @Mock LocatorResolver resolver;
    @Mock FrameNavigator frameNav;
    @Mock PopupSentinel sentinel;
    @Mock EvidenceCollector evidenceCollector;
    @Mock WebElement element;
    @Mock Alert alert;
    @Mock TargetLocator targetLocator;

    private AutoCloseable mocks;
    private PlayerEngine engine;

    @BeforeMethod
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);

        when(driver.getWindowHandles()).thenReturn(new HashSet<>(Set.of("main")));
        when(driver.getCurrentUrl()).thenReturn("https://example.com");
        when(driver.getTitle()).thenReturn("Example Domain");
        when(driver.switchTo()).thenReturn(targetLocator);
        when(targetLocator.alert()).thenReturn(alert);
        doNothing().when(driver).perform(any());  // suppress Interactive.perform

        engine = new PlayerEngine(driver, wait, resolver, frameNav, sentinel, evidenceCollector);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        mocks.close();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static RecordedEvent event(EventType type) {
        RecordedEvent e = new RecordedEvent();
        e.setEventType(type);
        e.setTimestamp(Instant.now());
        e.setUrl("https://example.com");
        return e;
    }

    private static ElementInfo ei(String id) {
        ElementInfo info = new ElementInfo();
        info.setId(id);
        return info;
    }

    private static RecordedSession session(RecordedEvent... events) {
        RecordedSession s = new RecordedSession();
        s.setSessionId("test-session");
        for (RecordedEvent e : events) {
            s.addEvent(e);
        }
        return s;
    }

    // ── NAVIGATE ──────────────────────────────────────────────────────────

    @Test(description = "NAVIGATE event drives browser to the specified URL")
    public void navigate_drivesToUrl() {
        RecordedEvent nav = event(EventType.NAVIGATE);
        nav.setUrl("https://example.com/page");

        PlayerEngine.PlaybackResult result = engine.play(session(nav));

        assertThat(result.isSuccess()).isTrue();
        verify(driver).get("https://example.com/page");
        verify(wait).waitForPageLoad();
    }

    @Test(description = "NAVIGATE event with null URL returns failure")
    public void navigate_nullUrl_fails() {
        RecordedEvent nav = event(EventType.NAVIGATE);
        nav.setUrl(null);

        PlayerEngine.PlaybackResult result = engine.play(session(nav));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).containsIgnoringCase("URL");
    }

    @Test(description = "NAVIGATE event with blank URL returns failure")
    public void navigate_blankUrl_fails() {
        RecordedEvent nav = event(EventType.NAVIGATE);
        nav.setUrl("   ");

        PlayerEngine.PlaybackResult result = engine.play(session(nav));

        assertThat(result.isSuccess()).isFalse();
    }

    // ── Auto-navigate ──────────────────────────────────────────────────────

    @Test(description = "Auto-navigate fires when first event lacks NAVIGATE but carries a URL")
    public void autoNavigate_firesWhenNoLeadingNavigate() {
        RecordedEvent click = event(EventType.CLICK);
        click.setUrl("https://example.com/app");
        click.setElement(ei("btn"));

        ElementLocator loc = new ElementLocator(Strategy.ID, "btn");
        when(resolver.resolve(any())).thenReturn(loc);
        when(wait.waitForClickable(By.id("btn"))).thenReturn(element);

        engine.play(session(click));

        // Auto-navigate should drive the browser to the URL before step 1
        verify(driver).get("https://example.com/app");
    }

    @Test(description = "Auto-navigate is skipped when first event is NAVIGATE")
    public void autoNavigate_skippedWhenFirstEventIsNavigate() {
        RecordedEvent nav = event(EventType.NAVIGATE);
        nav.setUrl("https://example.com");

        engine.play(session(nav));

        // Exactly one driver.get() — from the NAVIGATE handler, not auto-navigate
        verify(driver, times(1)).get("https://example.com");
    }

    // ── CLICK ─────────────────────────────────────────────────────────────

    @Test(description = "CLICK clicks the resolved element via waitForClickable")
    public void click_clicksResolvedElement() {
        RecordedEvent click = event(EventType.CLICK);
        click.setElement(ei("submit-btn"));

        ElementLocator loc = new ElementLocator(Strategy.ID, "submit-btn");
        when(resolver.resolve(any())).thenReturn(loc);
        when(wait.waitForClickable(By.id("submit-btn"))).thenReturn(element);

        PlayerEngine.PlaybackResult result = engine.play(session(click));

        assertThat(result.isSuccess()).isTrue();
        verify(element).click();
    }

    @Test(description = "CLICK falls back to JS coordinate click when locator fails")
    public void click_coordinateFallback_whenLocatorFails() {
        RecordedEvent click = event(EventType.CLICK);
        click.setElement(ei("ghost-btn"));
        Coordinates coords = new Coordinates(100.0, 200.0);
        click.setCoordinates(coords);

        when(resolver.resolve(any())).thenThrow(new AutoQAException("not found"));

        PlayerEngine.PlaybackResult result = engine.play(session(click));

        assertThat(result.isSuccess()).isTrue();
        verify(driver).executeScript(anyString(), eq(100.0), eq(200.0));
    }

    @Test(description = "CLICK with no element and no coordinates fails gracefully")
    public void click_noElementNoCoordinates_fails() {
        RecordedEvent click = event(EventType.CLICK);
        // no element, no coordinates

        PlayerEngine.PlaybackResult result = engine.play(session(click));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).containsIgnoringCase("no element and no coordinates");
    }

    // ── DOUBLE_CLICK ──────────────────────────────────────────────────────

    @Test(description = "DOUBLE_CLICK resolves element via resolver")
    public void doubleClick_resolvesElement() {
        RecordedEvent ev = event(EventType.DOUBLE_CLICK);
        ev.setElement(ei("dbl-btn"));
        when(resolver.findElement(any())).thenReturn(element);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
        verify(resolver).findElement(any());
    }

    @Test(description = "DOUBLE_CLICK with no element fails")
    public void doubleClick_noElement_fails() {
        RecordedEvent ev = event(EventType.DOUBLE_CLICK);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).containsIgnoringCase("DOUBLE_CLICK");
    }

    // ── INPUT ─────────────────────────────────────────────────────────────

    @Test(description = "INPUT clears field and sends keys")
    public void input_clearsAndSendsKeys() {
        RecordedEvent ev = event(EventType.INPUT);
        ev.setElement(ei("username"));
        InputData input = InputData.ofKeys("admin");
        ev.setInputData(input);
        when(resolver.findElement(any())).thenReturn(element);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
        verify(element).clear();
        verify(element).sendKeys("admin");
    }

    @Test(description = "INPUT with null keys sends empty string")
    public void input_nullKeys_sendsEmpty() {
        RecordedEvent ev = event(EventType.INPUT);
        ev.setElement(ei("field"));
        InputData input = new InputData();
        input.setKeys(null);
        ev.setInputData(input);
        when(resolver.findElement(any())).thenReturn(element);

        engine.play(session(ev));

        verify(element).sendKeys("");
    }

    @Test(description = "INPUT with no inputData fails")
    public void input_noInputData_fails() {
        RecordedEvent ev = event(EventType.INPUT);
        ev.setElement(ei("field"));
        // no inputData

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).containsIgnoringCase("inputData");
    }

    // ── KEY_PRESS ─────────────────────────────────────────────────────────

    @Test(description = "KEY_PRESS sends ENTER key to target element")
    public void keyPress_sendsEnterToElement() {
        RecordedEvent ev = event(EventType.KEY_PRESS);
        ev.setElement(ei("search-box"));
        ev.setInputData(InputData.ofKey("ENTER"));
        when(resolver.findElement(any())).thenReturn(element);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
        verify(element).sendKeys(Keys.ENTER);
    }

    @Test(description = "KEY_PRESS with unknown key code fails")
    public void keyPress_unknownKeyCode_fails() {
        RecordedEvent ev = event(EventType.KEY_PRESS);
        InputData input = new InputData();
        input.setKeyCode("SUPERKEY_99");
        ev.setInputData(input);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).containsIgnoringCase("Unknown Keys constant");
    }

    // ── SELECT ────────────────────────────────────────────────────────────

    @Test(description = "SELECT with no selectedOption fails")
    public void select_noSelectedOption_fails() {
        RecordedEvent ev = event(EventType.SELECT);
        ev.setElement(ei("dropdown"));
        ev.setInputData(new InputData());  // no selectedOption
        when(resolver.findElement(any())).thenReturn(element);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).containsIgnoringCase("selectedOption");
    }

    // ── SCROLL ────────────────────────────────────────────────────────────

    @Test(description = "SCROLL with element scrolls that element into view via JS")
    public void scroll_withElement_scrollsIntoView() {
        RecordedEvent ev = event(EventType.SCROLL);
        ev.setElement(ei("footer"));
        when(resolver.findElement(any())).thenReturn(element);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
        verify(driver).executeScript(contains("scrollIntoView"), eq(element));
    }

    @Test(description = "SCROLL without element scrolls to coordinates via JS")
    public void scroll_noElement_scrollsToCoordinates() {
        RecordedEvent ev = event(EventType.SCROLL);
        ev.setCoordinates(new Coordinates(0.0, 500.0));

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
        verify(driver).executeScript(contains("scrollTo"), eq(0.0), eq(500.0));
    }

    // ── ALERT ────────────────────────────────────────────────────────────

    @Test(description = "ALERT ACCEPT calls alert.accept()")
    public void alert_accept_callsAlertAccept() {
        RecordedEvent ev = event(EventType.ALERT);
        InputData input = new InputData();
        input.setAlertAction(AlertAction.ACCEPT);
        ev.setInputData(input);
        when(wait.waitForAlertPresent()).thenReturn(alert);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
        verify(alert).accept();
    }

    @Test(description = "ALERT DISMISS calls alert.dismiss()")
    public void alert_dismiss_callsAlertDismiss() {
        RecordedEvent ev = event(EventType.ALERT);
        InputData input = new InputData();
        input.setAlertAction(AlertAction.DISMISS);
        ev.setInputData(input);
        when(wait.waitForAlertPresent()).thenReturn(alert);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
        verify(alert).dismiss();
    }

    @Test(description = "ALERT defaults to ACCEPT when no inputData is present")
    public void alert_noInputData_defaultsToAccept() {
        RecordedEvent ev = event(EventType.ALERT);
        // no inputData
        when(wait.waitForAlertPresent()).thenReturn(alert);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
        verify(alert).accept();
    }

    // ── WINDOW_SWITCH ─────────────────────────────────────────────────────

    @Test(description = "WINDOW_SWITCH with explicit handle switches to that handle")
    public void windowSwitch_explicitHandle_switchesToIt() {
        RecordedEvent ev = event(EventType.WINDOW_SWITCH);
        ev.setWindowHandle("popup-handle");

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
        verify(targetLocator).window("popup-handle");
    }

    @Test(description = "WINDOW_SWITCH without handle waits for a new window to appear")
    public void windowSwitch_noHandle_waitsForNew() {
        RecordedEvent ev = event(EventType.WINDOW_SWITCH);
        when(wait.waitForNewWindow(any())).thenReturn("new-handle");

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
        verify(wait).waitForNewWindow(any());
        verify(targetLocator).window("new-handle");
    }

    // ── HOVER ────────────────────────────────────────────────────────────

    @Test(description = "HOVER resolves element via resolver")
    public void hover_resolvesElement() {
        RecordedEvent ev = event(EventType.HOVER);
        ev.setElement(ei("tooltip-trigger"));
        when(resolver.findElement(any())).thenReturn(element);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
        verify(resolver).findElement(any());
    }

    @Test(description = "HOVER with no element fails")
    public void hover_noElement_fails() {
        RecordedEvent ev = event(EventType.HOVER);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).containsIgnoringCase("HOVER");
    }

    // ── CHECKPOINT ────────────────────────────────────────────────────────

    @Test(description = "CHECKPOINT URL passes when URL matches exactly")
    public void checkpoint_url_exactMatch_passes() {
        RecordedEvent ev = event(EventType.CHECKPOINT);
        CheckpointData cp = new CheckpointData();
        cp.setCheckpointType(CheckpointType.URL);
        cp.setExpectedValue("https://example.com");
        cp.setMatchMode(MatchMode.EQUALS);
        cp.setCaseSensitive(false);
        ev.setCheckpointData(cp);
        when(driver.getCurrentUrl()).thenReturn("https://example.com");

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test(description = "CHECKPOINT URL fails when URL does not match")
    public void checkpoint_url_exactMatch_fails() {
        RecordedEvent ev = event(EventType.CHECKPOINT);
        CheckpointData cp = new CheckpointData();
        cp.setCheckpointType(CheckpointType.URL);
        cp.setExpectedValue("https://other.com");
        cp.setMatchMode(MatchMode.EQUALS);
        cp.setCaseSensitive(false);
        ev.setCheckpointData(cp);
        when(driver.getCurrentUrl()).thenReturn("https://example.com");

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).containsIgnoringCase("Checkpoint URL failed");
    }

    @Test(description = "CHECKPOINT TITLE passes with CONTAINS match mode")
    public void checkpoint_title_containsMatch_passes() {
        RecordedEvent ev = event(EventType.CHECKPOINT);
        CheckpointData cp = new CheckpointData();
        cp.setCheckpointType(CheckpointType.TITLE);
        cp.setExpectedValue("Example");
        cp.setMatchMode(MatchMode.CONTAINS);
        cp.setCaseSensitive(true);
        ev.setCheckpointData(cp);
        when(driver.getTitle()).thenReturn("Welcome to Example Domain");

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test(description = "CHECKPOINT TEXT passes when element text matches exactly")
    public void checkpoint_text_exactMatch_passes() {
        RecordedEvent ev = event(EventType.CHECKPOINT);
        ev.setElement(ei("heading"));
        CheckpointData cp = new CheckpointData();
        cp.setCheckpointType(CheckpointType.TEXT);
        cp.setExpectedValue("Hello World");
        cp.setMatchMode(MatchMode.EQUALS);
        cp.setCaseSensitive(true);
        ev.setCheckpointData(cp);

        when(resolver.findElement(any())).thenReturn(element);
        when(element.getText()).thenReturn("Hello World");

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test(description = "CHECKPOINT TEXT fails when element text does not match")
    public void checkpoint_text_mismatch_fails() {
        RecordedEvent ev = event(EventType.CHECKPOINT);
        ev.setElement(ei("heading"));
        CheckpointData cp = new CheckpointData();
        cp.setCheckpointType(CheckpointType.TEXT);
        cp.setExpectedValue("Expected Text");
        cp.setMatchMode(MatchMode.EQUALS);
        cp.setCaseSensitive(true);
        ev.setCheckpointData(cp);

        when(resolver.findElement(any())).thenReturn(element);
        when(element.getText()).thenReturn("Actual Different Text");

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).containsIgnoringCase("Checkpoint TEXT failed");
    }

    @Test(description = "CHECKPOINT ELEMENT_EXISTS passes when element is found")
    public void checkpoint_elementExists_passes() {
        RecordedEvent ev = event(EventType.CHECKPOINT);
        ev.setElement(ei("save-btn"));
        CheckpointData cp = new CheckpointData();
        cp.setCheckpointType(CheckpointType.ELEMENT_EXISTS);
        ev.setCheckpointData(cp);
        when(resolver.findElement(any())).thenReturn(element);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test(description = "CHECKPOINT ELEMENT_EXISTS fails when element is not found")
    public void checkpoint_elementExists_fails() {
        RecordedEvent ev = event(EventType.CHECKPOINT);
        ev.setElement(ei("missing-btn"));
        CheckpointData cp = new CheckpointData();
        cp.setCheckpointType(CheckpointType.ELEMENT_EXISTS);
        ev.setCheckpointData(cp);
        when(resolver.findElement(any())).thenThrow(new AutoQAException("not found"));

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).containsIgnoringCase("ELEMENT_EXISTS");
    }

    @Test(description = "CHECKPOINT REGEX match mode works correctly")
    public void checkpoint_regex_matchMode_passes() {
        RecordedEvent ev = event(EventType.CHECKPOINT);
        CheckpointData cp = new CheckpointData();
        cp.setCheckpointType(CheckpointType.URL);
        cp.setExpectedValue("https://example\\.com.*");
        cp.setMatchMode(MatchMode.REGEX);
        cp.setCaseSensitive(true);
        ev.setCheckpointData(cp);
        when(driver.getCurrentUrl()).thenReturn("https://example.com/path?q=1");

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
    }

    // ── Object Repository ─────────────────────────────────────────────────

    @Test(description = "OR-backed CLICK resolves named test object when inline element is absent")
    public void or_resolvesNamedObject() {
        RecordedEvent ev = event(EventType.CLICK);
        ev.setObjectName("LoginButton");
        // no inline element — must come from OR

        TestObject testObj = new TestObject("LoginButton", "Button");
        testObj.addLocator(Strategy.ID, "login-btn");

        ObjectRepository or = new ObjectRepository();
        or.add(testObj);
        engine.setObjectRepository(or);

        // After OR resolution, event.getElement() has the locator from TestObject
        ElementLocator loc = new ElementLocator(Strategy.ID, "login-btn");
        when(resolver.resolve(any())).thenReturn(loc);
        when(wait.waitForClickable(any(By.class))).thenReturn(element);

        PlayerEngine.PlaybackResult result = engine.play(session(ev));

        assertThat(result.isSuccess()).isTrue();
        verify(element).click();
    }

    // ── DRAG_DROP ─────────────────────────────────────────────────────────

    @Test(description = "DRAG_DROP is skipped with a warning and does not fail playback")
    public void dragDrop_isSkipped_doesNotFail() {
        RecordedEvent nav = event(EventType.NAVIGATE);
        nav.setUrl("https://example.com");

        RecordedEvent drag = event(EventType.DRAG_DROP);

        PlayerEngine.PlaybackResult result = engine.play(session(nav, drag));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStepsCompleted()).isEqualTo(2);
    }

    // ── Empty session ──────────────────────────────────────────────────────

    @Test(description = "Empty session plays back successfully with 0 steps completed")
    public void emptySession_succeeds() {
        PlayerEngine.PlaybackResult result = engine.play(session());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStepsCompleted()).isEqualTo(0);
        assertThat(result.getTotalSteps()).isEqualTo(0);
    }

    // ── PlaybackResult ────────────────────────────────────────────────────

    @Test(description = "PlaybackResult success toString includes SUCCESS and step count")
    public void playbackResult_toString_success() {
        PlayerEngine.PlaybackResult r = new PlayerEngine.PlaybackResult(true, 7, 7, null);

        assertThat(r.toString()).contains("SUCCESS").contains("7/7");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getStepsCompleted()).isEqualTo(7);
        assertThat(r.getTotalSteps()).isEqualTo(7);
        assertThat(r.getFailureReason()).isNull();
    }

    @Test(description = "PlaybackResult failure toString includes FAILED and reason")
    public void playbackResult_toString_failure() {
        PlayerEngine.PlaybackResult r = new PlayerEngine.PlaybackResult(false, 3, 7, "element not found");

        assertThat(r.toString()).contains("FAILED").contains("element not found");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getFailureReason()).isEqualTo("element not found");
    }

    // ── Multi-step session ────────────────────────────────────────────────

    @Test(description = "Multi-step session executes all steps in order")
    public void multiStep_allStepsExecuted() {
        RecordedEvent nav = event(EventType.NAVIGATE);
        nav.setUrl("https://example.com");

        RecordedEvent click = event(EventType.CLICK);
        click.setElement(ei("next-btn"));
        ElementLocator loc = new ElementLocator(Strategy.ID, "next-btn");
        when(resolver.resolve(any())).thenReturn(loc);
        when(wait.waitForClickable(any(By.class))).thenReturn(element);

        RecordedEvent nav2 = event(EventType.NAVIGATE);
        nav2.setUrl("https://example.com/page2");

        PlayerEngine.PlaybackResult result = engine.play(session(nav, click, nav2));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStepsCompleted()).isEqualTo(3);
        assertThat(result.getTotalSteps()).isEqualTo(3);
    }
}
