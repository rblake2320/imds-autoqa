package autoqa.ai;

import autoqa.model.ElementInfo;
import autoqa.model.ElementLocator;
import autoqa.player.AutoQAException;
import autoqa.player.LocatorResolver;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HealingInterceptor}.
 *
 * <p>All collaborators ({@link LocatorResolver}, {@link LocatorHealer},
 * {@link WebDriver}) are Mockito mocks, keeping these tests fast and
 * isolated from any browser or network dependency.
 */
public class HealingInterceptorTest {

    // ── Happy path — no healing needed ───────────────────────────────────

    @Test
    public void findElement_success_noHealingNeeded() {
        LocatorResolver resolver = mock(LocatorResolver.class);
        WebElement element = mock(WebElement.class);
        when(resolver.findElement(any())).thenReturn(element);

        WebDriver driver = mock(WebDriver.class);
        LocatorHealer healer = mock(LocatorHealer.class);
        HealingInterceptor interceptor = new HealingInterceptor(resolver, healer, driver);

        WebElement result = interceptor.findElement(new ElementInfo());

        assertThat(result).isEqualTo(element);
        verifyNoInteractions(healer);
    }

    // ── Resolver throws AutoQAException → LLM healing succeeds (CSS) ─────

    @Test
    public void findElement_resolverFails_healingSucceeds() {
        LocatorResolver resolver = mock(LocatorResolver.class);
        when(resolver.findElement(any())).thenThrow(new AutoQAException("not found"));

        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn(
                "<html><body><button id='new-btn'>OK</button></body></html>");
        when(driver.getCurrentUrl()).thenReturn("http://example.com");

        WebElement healedElement = mock(WebElement.class);
        when(driver.findElement(By.cssSelector("#new-btn"))).thenReturn(healedElement);

        LocatorHealer healer = mock(LocatorHealer.class);
        when(healer.heal(any(), any(), any())).thenReturn(
                LocatorHealer.HealingResult.success("#new-btn", ElementLocator.Strategy.CSS));

        HealingInterceptor interceptor = new HealingInterceptor(resolver, healer, driver);

        WebElement found = interceptor.findElement(new ElementInfo());

        assertThat(found).isEqualTo(healedElement);
    }

    // ── Resolver throws NoSuchElementException → LLM healing succeeds (XPath) ──

    @Test
    public void findElement_noSuchElementException_healingSucceeds() {
        LocatorResolver resolver = mock(LocatorResolver.class);
        when(resolver.findElement(any())).thenThrow(new NoSuchElementException("element not present"));

        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn("<html></html>");
        when(driver.getCurrentUrl()).thenReturn("http://example.com");

        WebElement healedElement = mock(WebElement.class);
        String healedXpath = "//button[@data-id='submit']";
        when(driver.findElement(By.xpath(healedXpath))).thenReturn(healedElement);

        LocatorHealer healer = mock(LocatorHealer.class);
        when(healer.heal(any(), any(), any())).thenReturn(
                LocatorHealer.HealingResult.success(healedXpath, ElementLocator.Strategy.XPATH));

        HealingInterceptor interceptor = new HealingInterceptor(resolver, healer, driver);

        assertThat(interceptor.findElement(new ElementInfo())).isEqualTo(healedElement);
    }

    // ── LLM fails → DOM fallback succeeds ────────────────────────────────

    @Test
    public void findElement_llmFails_domFallbackSucceeds() {
        LocatorResolver resolver = mock(LocatorResolver.class);
        when(resolver.findElement(any())).thenThrow(new AutoQAException("not found"));

        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn("<html></html>");
        when(driver.getCurrentUrl()).thenReturn("http://example.com");

        String fallbackXpath = "//button[contains(normalize-space(text()),'Submit')]";
        WebElement fallbackElement = mock(WebElement.class);
        when(driver.findElement(By.xpath(fallbackXpath))).thenReturn(fallbackElement);

        LocatorHealer healer = mock(LocatorHealer.class);
        when(healer.heal(any(), any(), any())).thenReturn(
                LocatorHealer.HealingResult.failed("LLM returned CANNOT_HEAL"));
        when(healer.healByDomComparison(any(), any())).thenReturn(
                LocatorHealer.HealingResult.success(fallbackXpath, ElementLocator.Strategy.XPATH));

        HealingInterceptor interceptor = new HealingInterceptor(resolver, healer, driver);

        assertThat(interceptor.findElement(new ElementInfo())).isEqualTo(fallbackElement);
        // Verify DOM fallback was called after LLM failed
        verify(healer).healByDomComparison(any(), any());
    }

    // ── All healing strategies fail ───────────────────────────────────────

    @Test
    public void findElement_allHealingFails_throwsAutoQAException() {
        LocatorResolver resolver = mock(LocatorResolver.class);
        when(resolver.findElement(any())).thenThrow(new AutoQAException("not found"));

        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn("<html></html>");
        when(driver.getCurrentUrl()).thenReturn("http://example.com");

        LocatorHealer healer = mock(LocatorHealer.class);
        when(healer.heal(any(), any(), any())).thenReturn(
                LocatorHealer.HealingResult.failed("cannot heal"));
        when(healer.healByDomComparison(any(), any())).thenReturn(
                LocatorHealer.HealingResult.failed("no text"));

        HealingInterceptor interceptor = new HealingInterceptor(resolver, healer, driver);

        assertThatThrownBy(() -> interceptor.findElement(new ElementInfo()))
                .isInstanceOf(AutoQAException.class)
                .hasMessageContaining("healing failed");
    }

    // ── Healed locator itself throws NoSuchElementException ──────────────

    @Test
    public void findElement_healedLocatorNotFound_throwsAutoQAException() {
        LocatorResolver resolver = mock(LocatorResolver.class);
        when(resolver.findElement(any())).thenThrow(new AutoQAException("not found"));

        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn("<html></html>");
        when(driver.getCurrentUrl()).thenReturn("http://example.com");
        // The healed locator also fails at the DOM level
        when(driver.findElement(any())).thenThrow(new NoSuchElementException("healed locator miss"));

        LocatorHealer healer = mock(LocatorHealer.class);
        when(healer.heal(any(), any(), any())).thenReturn(
                LocatorHealer.HealingResult.success("#ghost", ElementLocator.Strategy.CSS));

        HealingInterceptor interceptor = new HealingInterceptor(resolver, healer, driver);

        assertThatThrownBy(() -> interceptor.findElement(new ElementInfo()))
                .isInstanceOf(AutoQAException.class)
                .hasMessageContaining("#ghost");
    }

    // ── Verify healer receives page source and URL ────────────────────────

    @Test
    public void findElement_resolverFails_passesDomContextToHealer() {
        LocatorResolver resolver = mock(LocatorResolver.class);
        when(resolver.findElement(any())).thenThrow(new AutoQAException("not found"));

        String pageSource = "<html><body>test page</body></html>";
        String pageUrl    = "http://test.example.com/page";

        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn(pageSource);
        when(driver.getCurrentUrl()).thenReturn(pageUrl);

        WebElement healed = mock(WebElement.class);
        when(driver.findElement(any())).thenReturn(healed);

        LocatorHealer healer = mock(LocatorHealer.class);
        when(healer.heal(any(), any(), any())).thenReturn(
                LocatorHealer.HealingResult.success("#el", ElementLocator.Strategy.CSS));

        HealingInterceptor interceptor = new HealingInterceptor(resolver, healer, driver);
        interceptor.findElement(new ElementInfo());

        // Verify the healer was called with the actual page source and URL
        verify(healer).heal(any(ElementInfo.class), org.mockito.ArgumentMatchers.eq(pageSource),
                org.mockito.ArgumentMatchers.eq(pageUrl));
    }

    // ── Healing is only attempted once per call ───────────────────────────

    @Test
    public void findElement_healingAttemptedOnlyOnce() {
        LocatorResolver resolver = mock(LocatorResolver.class);
        when(resolver.findElement(any())).thenThrow(new AutoQAException("not found"));

        WebDriver driver = mock(WebDriver.class);
        when(driver.getPageSource()).thenReturn("<html></html>");
        when(driver.getCurrentUrl()).thenReturn("http://example.com");

        LocatorHealer healer = mock(LocatorHealer.class);
        when(healer.heal(any(), any(), any())).thenReturn(
                LocatorHealer.HealingResult.failed("nope"));
        when(healer.healByDomComparison(any(), any())).thenReturn(
                LocatorHealer.HealingResult.failed("nope too"));

        HealingInterceptor interceptor = new HealingInterceptor(resolver, healer, driver);

        assertThatThrownBy(() -> interceptor.findElement(new ElementInfo()))
                .isInstanceOf(AutoQAException.class);

        // heal() called exactly once — no infinite retry loop
        verify(healer, org.mockito.Mockito.times(1)).heal(any(), any(), any());
        verify(healer, org.mockito.Mockito.times(1)).healByDomComparison(any(), any());
    }
}
