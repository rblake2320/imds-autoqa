package autoqa.player;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openqa.selenium.Alert;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriver.TargetLocator;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PopupSentinel} using Mockito mocks.
 *
 * <p>All tests are pure unit tests — no real browser is launched.
 */
public class PopupSentinelTest {

    @Mock private WebDriver driver;
    @Mock private TargetLocator targetLocator;
    @Mock private Alert alert;
    @Mock private WebDriver.Options options;
    @Mock private WebDriver.Window window;

    private AutoCloseable mocks;
    private PopupSentinel sentinel;

    @BeforeMethod
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);

        // Wire up driver.getWindowHandles() to return a single default handle so the
        // constructor does not throw.
        when(driver.getWindowHandles()).thenReturn(Set.of("handle-1"));

        sentinel = new PopupSentinel(driver);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        mocks.close();
    }

    // ── No popup ──────────────────────────────────────────────────────────

    /**
     * When there is no alert and the window count matches expectations,
     * check() should return false and not perform any switch.
     */
    @Test
    public void check_returnsfalse_whenNoPopupPresent() {
        when(driver.switchTo()).thenReturn(targetLocator);
        when(targetLocator.alert()).thenThrow(new NoAlertPresentException("no alert"));
        when(driver.getWindowHandles()).thenReturn(Set.of("handle-1"));
        // findElements will return empty list by default via Mockito (returns null → NPE risk)
        // Use lenient stubbing to return empty list for any By selector
        when(driver.findElements(any())).thenReturn(java.util.List.of());

        boolean result = sentinel.check();

        assertThat(result).isFalse();
        verify(targetLocator).alert();            // attempted alert check
        verify(targetLocator, never()).window(anyString()); // no window switch
    }

    // ── Alert present ─────────────────────────────────────────────────────

    /**
     * When an unexpected alert is present, check() should:
     *  - detect it via driver.switchTo().alert()
     *  - call alert.dismiss()
     *  - return true
     */
    @Test
    public void check_returnsTrue_andDismissesAlert_whenAlertIsPresent() {
        when(driver.switchTo()).thenReturn(targetLocator);
        when(targetLocator.alert()).thenReturn(alert);
        when(alert.getText()).thenReturn("Unexpected popup!");
        when(driver.getWindowHandles()).thenReturn(Set.of("handle-1"));
        when(driver.findElements(any())).thenReturn(java.util.List.of());

        boolean result = sentinel.check();

        assertThat(result).isTrue();
        verify(alert).dismiss();
        verify(alert, never()).accept(); // dismiss, not accept
    }

    /**
     * If alert.getText() itself throws, the sentinel should still dismiss gracefully
     * without propagating the exception.
     */
    @Test
    public void check_stillDismisses_whenGetTextThrows() {
        when(driver.switchTo()).thenReturn(targetLocator);
        when(targetLocator.alert()).thenReturn(alert);
        when(alert.getText()).thenThrow(new RuntimeException("getText failed"));
        when(driver.getWindowHandles()).thenReturn(Set.of("handle-1"));
        when(driver.findElements(any())).thenReturn(java.util.List.of());

        boolean result = sentinel.check();

        assertThat(result).isTrue();
        verify(alert).dismiss();
    }

    // ── Extra window ──────────────────────────────────────────────────────

    /**
     * When an extra window appears unexpectedly, check() should log a warning
     * but NOT switch windows, and return false (window switching is not auto-handled).
     */
    @Test
    public void check_returnsFalse_andDoesNotSwitch_whenExtraWindowDetected() {
        // Sentinel was constructed with 1 window; now there are 2
        when(driver.switchTo()).thenReturn(targetLocator);
        when(targetLocator.alert()).thenThrow(new NoAlertPresentException("no alert"));
        when(driver.getWindowHandles()).thenReturn(Set.of("handle-1", "handle-2"));
        when(driver.findElements(any())).thenReturn(java.util.List.of());

        boolean result = sentinel.check();

        assertThat(result).isFalse();
        verify(targetLocator, never()).window(anyString());
    }

    // ── DOM modal ─────────────────────────────────────────────────────────

    /**
     * A visible DOM modal element should be detected (warning logged) but NOT
     * auto-closed; check() returns false.
     */
    @Test
    public void check_returnsFalse_andDoesNotClose_whenDomModalVisible() {
        org.openqa.selenium.WebElement fakeModal = mock(org.openqa.selenium.WebElement.class);
        when(fakeModal.isDisplayed()).thenReturn(true);

        when(driver.switchTo()).thenReturn(targetLocator);
        when(targetLocator.alert()).thenThrow(new NoAlertPresentException("no alert"));
        when(driver.getWindowHandles()).thenReturn(Set.of("handle-1"));
        // Return the fake modal for the first By selector; empty for the rest
        when(driver.findElements(any()))
                .thenReturn(java.util.List.of(fakeModal))  // first call — role=dialog
                .thenReturn(java.util.List.of());           // remaining calls

        boolean result = sentinel.check();

        assertThat(result).isFalse();
        // No click/close on the modal
        verify(fakeModal, never()).click();
    }

    // ── updateExpectedWindowCount ─────────────────────────────────────────

    /**
     * After calling updateExpectedWindowCount(2), having 2 windows open
     * should not trigger a warning.
     */
    @Test
    public void check_noWarning_afterExpectedCountUpdated() {
        when(driver.switchTo()).thenReturn(targetLocator);
        when(targetLocator.alert()).thenThrow(new NoAlertPresentException("no alert"));
        when(driver.getWindowHandles()).thenReturn(Set.of("handle-1", "handle-2"));
        when(driver.findElements(any())).thenReturn(java.util.List.of());

        sentinel.updateExpectedWindowCount(2);

        boolean result = sentinel.check();

        assertThat(result).isFalse();
        // If no exception is thrown and result is false, test passes
        // The warning would only appear in logs — we can't easily assert on logs here
        // but the important contract is: no window switch and no alert handling
        verify(targetLocator, never()).window(anyString());
    }
}
