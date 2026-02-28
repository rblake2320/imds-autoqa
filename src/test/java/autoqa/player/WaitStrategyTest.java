package autoqa.player;

import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockitoAnnotations;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WaitStrategy}.
 *
 * <p>Because {@link WebDriverWait} is a concrete class that drives actual
 * polling, these tests mock it via {@link MockedConstruction} to verify that:
 * <ul>
 *   <li>Each public method delegates to the correct
 *       {@code ExpectedConditions} or lambda.</li>
 *   <li>A {@link TimeoutException} from the underlying wait is wrapped in
 *       an {@link AutoQAException}.</li>
 * </ul>
 */
public class WaitStrategyTest {

    @Mock
    private WebDriver driver;

    @Mock
    private WebElement mockElement;

    @Mock
    private Alert mockAlert;

    private AutoCloseable mocks;

    @BeforeMethod
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        mocks.close();
    }

    // ── waitForPresent ────────────────────────────────────────────────────

    @Test(description = "waitForPresent returns element when wait succeeds")
    public void testWaitForPresentSuccess() {
        By locator = By.id("some-id");

        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any(ExpectedCondition.class)))
                                .thenReturn(mockElement))) {

            WaitStrategy ws = new WaitStrategy(driver, 10);
            WebElement result = ws.waitForPresent(locator);

            assertThat(result).isSameAs(mockElement);
        }
    }

    @Test(description = "waitForPresent wraps TimeoutException in AutoQAException")
    public void testWaitForPresentTimeout() {
        By locator = By.id("missing");

        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any(ExpectedCondition.class)))
                                .thenThrow(new TimeoutException("timed out")))) {

            WaitStrategy ws = new WaitStrategy(driver, 5);

            assertThatThrownBy(() -> ws.waitForPresent(locator))
                    .isInstanceOf(AutoQAException.class)
                    .hasMessageContaining("present")
                    .hasMessageContaining("By.id: missing");
        }
    }

    // ── waitForVisible ────────────────────────────────────────────────────

    @Test(description = "waitForVisible returns element when wait succeeds")
    public void testWaitForVisibleSuccess() {
        By locator = By.cssSelector(".my-div");

        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any(ExpectedCondition.class)))
                                .thenReturn(mockElement))) {

            WaitStrategy ws = new WaitStrategy(driver, 10);
            WebElement result = ws.waitForVisible(locator);

            assertThat(result).isSameAs(mockElement);
        }
    }

    @Test(description = "waitForVisible wraps timeout in AutoQAException mentioning 'visible'")
    public void testWaitForVisibleTimeout() {
        By locator = By.cssSelector(".hidden");

        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any(ExpectedCondition.class)))
                                .thenThrow(new TimeoutException("timed out")))) {

            WaitStrategy ws = new WaitStrategy(driver, 5);

            assertThatThrownBy(() -> ws.waitForVisible(locator))
                    .isInstanceOf(AutoQAException.class)
                    .hasMessageContaining("visible");
        }
    }

    // ── waitForClickable ──────────────────────────────────────────────────

    @Test(description = "waitForClickable returns element when wait succeeds")
    public void testWaitForClickableSuccess() {
        By locator = By.name("submit");

        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any(ExpectedCondition.class)))
                                .thenReturn(mockElement))) {

            WaitStrategy ws = new WaitStrategy(driver, 10);
            WebElement result = ws.waitForClickable(locator);

            assertThat(result).isSameAs(mockElement);
        }
    }

    @Test(description = "waitForClickable wraps timeout in AutoQAException mentioning 'clickable'")
    public void testWaitForClickableTimeout() {
        By locator = By.name("disabled-btn");

        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any(ExpectedCondition.class)))
                                .thenThrow(new TimeoutException("timed out")))) {

            WaitStrategy ws = new WaitStrategy(driver, 5);

            assertThatThrownBy(() -> ws.waitForClickable(locator))
                    .isInstanceOf(AutoQAException.class)
                    .hasMessageContaining("clickable");
        }
    }

    // ── waitForPageLoad ───────────────────────────────────────────────────

    @Test(description = "waitForPageLoad completes without exception when wait returns true")
    public void testWaitForPageLoadSuccess() {
        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any())).thenReturn(true))) {

            WaitStrategy ws = new WaitStrategy(driver, 10);
            ws.waitForPageLoad();   // must not throw
        }
    }

    @Test(description = "waitForPageLoad wraps timeout in AutoQAException")
    public void testWaitForPageLoadTimeout() {
        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any()))
                                .thenThrow(new TimeoutException("page load timed out")))) {

            WaitStrategy ws = new WaitStrategy(driver, 5);

            assertThatThrownBy(ws::waitForPageLoad)
                    .isInstanceOf(AutoQAException.class)
                    .hasMessageContaining("page");
        }
    }

    // ── waitForUrlContains ────────────────────────────────────────────────

    @Test(description = "waitForUrlContains completes without exception on success")
    public void testWaitForUrlContainsSuccess() {
        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any(ExpectedCondition.class)))
                                .thenReturn(true))) {

            WaitStrategy ws = new WaitStrategy(driver, 10);
            ws.waitForUrlContains("/dashboard");  // must not throw
        }
    }

    @Test(description = "waitForUrlContains wraps timeout with fragment in message")
    public void testWaitForUrlContainsTimeout() {
        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any(ExpectedCondition.class)))
                                .thenThrow(new TimeoutException("url not matched")))) {

            WaitStrategy ws = new WaitStrategy(driver, 5);

            assertThatThrownBy(() -> ws.waitForUrlContains("/admin"))
                    .isInstanceOf(AutoQAException.class)
                    .hasMessageContaining("/admin");
        }
    }

    // ── waitForAlertPresent ───────────────────────────────────────────────

    @Test(description = "waitForAlertPresent returns Alert on success")
    public void testWaitForAlertPresentSuccess() {
        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any(ExpectedCondition.class)))
                                .thenReturn(mockAlert))) {

            WaitStrategy ws = new WaitStrategy(driver, 10);
            Alert result = ws.waitForAlertPresent();

            assertThat(result).isSameAs(mockAlert);
        }
    }

    @Test(description = "waitForAlertPresent wraps timeout in AutoQAException mentioning 'alert'")
    public void testWaitForAlertPresentTimeout() {
        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any(ExpectedCondition.class)))
                                .thenThrow(new TimeoutException("no alert")))) {

            WaitStrategy ws = new WaitStrategy(driver, 5);

            assertThatThrownBy(ws::waitForAlertPresent)
                    .isInstanceOf(AutoQAException.class)
                    .hasMessageContaining("alert");
        }
    }

    // ── waitForNewWindow ──────────────────────────────────────────────────

    @Test(description = "waitForNewWindow returns the new window handle on success")
    public void testWaitForNewWindowSuccess() {
        Set<String> known = new HashSet<>();
        known.add("handle-1");

        Set<String> allHandles = new HashSet<>();
        allHandles.add("handle-1");
        allHandles.add("handle-2");   // the new one

        // driver.getWindowHandles() returns the expanded set after the wait
        when(driver.getWindowHandles()).thenReturn(allHandles);

        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any())).thenReturn(true))) {

            WaitStrategy ws = new WaitStrategy(driver, 10);
            String newHandle = ws.waitForNewWindow(known);

            assertThat(newHandle).isEqualTo("handle-2");
        }
    }

    @Test(description = "waitForNewWindow wraps timeout in AutoQAException mentioning 'window'")
    public void testWaitForNewWindowTimeout() {
        Set<String> known = new HashSet<>();
        known.add("handle-1");

        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any()))
                                .thenThrow(new TimeoutException("no new window")))) {

            WaitStrategy ws = new WaitStrategy(driver, 5);

            assertThatThrownBy(() -> ws.waitForNewWindow(known))
                    .isInstanceOf(AutoQAException.class)
                    .hasMessageContaining("window");
        }
    }

    // ── Timeout value propagation ─────────────────────────────────────────

    @Test(description = "Timeout value in seconds is included in AutoQAException message")
    public void testTimeoutSecondsAppearsInExceptionMessage() {
        By locator = By.id("slow-elem");
        int timeoutSec = 42;

        try (MockedConstruction<WebDriverWait> wdwMock =
                mockConstruction(WebDriverWait.class,
                        (mock, ctx) -> when(mock.until(any(ExpectedCondition.class)))
                                .thenThrow(new TimeoutException("timed out")))) {

            WaitStrategy ws = new WaitStrategy(driver, timeoutSec);

            assertThatThrownBy(() -> ws.waitForPresent(locator))
                    .isInstanceOf(AutoQAException.class)
                    .hasMessageContaining(String.valueOf(timeoutSec));
        }
    }
}
