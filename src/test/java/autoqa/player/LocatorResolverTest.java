package autoqa.player;

import autoqa.model.ElementInfo;
import autoqa.model.ElementLocator;
import autoqa.model.ElementLocator.Strategy;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link LocatorResolver}.
 *
 * <p>Verifies the strategy priority order (ID → Name → CSS → XPath) and that
 * an {@link AutoQAException} is raised when all strategies fail.
 */
public class LocatorResolverTest {

    @Mock
    private WebDriver driver;

    @Mock
    private WaitStrategy wait;

    @Mock
    private WebElement mockElement;

    private AutoCloseable mocks;

    @BeforeMethod
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        mocks.close();
    }

    // ── Helper builders ───────────────────────────────────────────────────

    /** Builds an ElementInfo with all four locators populated. */
    private ElementInfo fullElement(String id, String name, String css, String xpath) {
        ElementInfo ei = new ElementInfo();
        ei.setId(id);
        ei.setName(name);
        ei.setCss(css);
        ei.setXpath(xpath);
        return ei;
    }

    /** Builds an ElementInfo with only the given locator set. */
    private ElementInfo singleLocator(String id, String name, String css, String xpath) {
        return fullElement(id, name, css, xpath);
    }

    private LocatorResolver resolver(int maxAttempts) {
        return new LocatorResolver(driver, wait, maxAttempts);
    }

    // ── Strategy priority: ID wins first ─────────────────────────────────

    @Test(description = "ID strategy succeeds when element is found by ID")
    public void testIdFoundFirst() {
        when(driver.findElement(By.id("btn-submit"))).thenReturn(mockElement);

        ElementInfo ei = fullElement("btn-submit", "submit", "#btn-submit", "//button");
        LocatorResolver r = resolver(4);

        ElementLocator result = r.resolve(ei);

        assertThat(result.getStrategy()).isEqualTo(Strategy.ID);
        assertThat(result.getValue()).isEqualTo("btn-submit");
        verify(driver, times(1)).findElement(By.id("btn-submit"));
        // Name / CSS / XPath should not have been attempted
        verify(driver, never()).findElement(By.name(any()));
        verify(driver, never()).findElement(By.cssSelector(any()));
        verify(driver, never()).findElement(By.xpath(any()));
    }

    // ── Strategy priority: Name tried when ID fails ───────────────────────

    @Test(description = "Name strategy used when ID is absent or not found")
    public void testNameFallbackWhenIdFails() {
        // ID throws NoSuchElement
        when(driver.findElement(By.id("btn"))).thenThrow(new NoSuchElementException("no id"));
        when(driver.findElement(By.name("submit-btn"))).thenReturn(mockElement);

        ElementInfo ei = fullElement("btn", "submit-btn", "#btn", "//button");
        LocatorResolver r = resolver(4);

        ElementLocator result = r.resolve(ei);

        assertThat(result.getStrategy()).isEqualTo(Strategy.NAME);
        assertThat(result.getValue()).isEqualTo("submit-btn");
        verify(driver, times(1)).findElement(By.id("btn"));
        verify(driver, times(1)).findElement(By.name("submit-btn"));
    }

    @Test(description = "Name strategy used when ID field is null")
    public void testNameUsedWhenIdIsNull() {
        when(driver.findElement(By.name("myField"))).thenReturn(mockElement);

        ElementInfo ei = fullElement(null, "myField", null, null);
        LocatorResolver r = resolver(4);

        ElementLocator result = r.resolve(ei);

        assertThat(result.getStrategy()).isEqualTo(Strategy.NAME);
        verify(driver, never()).findElement(By.id(any()));
    }

    // ── Strategy priority: CSS tried when Name fails ──────────────────────

    @Test(description = "CSS strategy used when both ID and Name fail")
    public void testCssFallbackWhenIdAndNameFail() {
        when(driver.findElement(By.id("x"))).thenThrow(new NoSuchElementException("no id"));
        when(driver.findElement(By.name("x"))).thenThrow(new NoSuchElementException("no name"));
        when(driver.findElement(By.cssSelector("#x"))).thenReturn(mockElement);

        ElementInfo ei = fullElement("x", "x", "#x", "//div[@id='x']");
        LocatorResolver r = resolver(4);

        ElementLocator result = r.resolve(ei);

        assertThat(result.getStrategy()).isEqualTo(Strategy.CSS);
        verify(driver, times(1)).findElement(By.id("x"));
        verify(driver, times(1)).findElement(By.name("x"));
        verify(driver, times(1)).findElement(By.cssSelector("#x"));
        verify(driver, never()).findElement(By.xpath(any()));
    }

    // ── Strategy priority: XPath tried last ──────────────────────────────

    @Test(description = "XPath strategy used when ID, Name, and CSS all fail")
    public void testXpathFallbackWhenAllOthersFail() {
        when(driver.findElement(By.id("z"))).thenThrow(new NoSuchElementException("no id"));
        when(driver.findElement(By.name("z"))).thenThrow(new NoSuchElementException("no name"));
        when(driver.findElement(By.cssSelector(".z"))).thenThrow(new NoSuchElementException("no css"));
        when(driver.findElement(By.xpath("//span[@class='z']"))).thenReturn(mockElement);

        ElementInfo ei = fullElement("z", "z", ".z", "//span[@class='z']");
        LocatorResolver r = resolver(4);

        ElementLocator result = r.resolve(ei);

        assertThat(result.getStrategy()).isEqualTo(Strategy.XPATH);
        verify(driver, times(1)).findElement(By.id("z"));
        verify(driver, times(1)).findElement(By.name("z"));
        verify(driver, times(1)).findElement(By.cssSelector(".z"));
        verify(driver, times(1)).findElement(By.xpath("//span[@class='z']"));
    }

    // ── All strategies fail → AutoQAException ────────────────────────────

    @Test(description = "AutoQAException thrown when all four strategies fail")
    public void testAllStrategiesFailThrowsException() {
        when(driver.findElement(any(By.class)))
                .thenThrow(new NoSuchElementException("not found"));

        ElementInfo ei = fullElement("bad-id", "bad-name", ".bad-css", "//bad");
        LocatorResolver r = resolver(4);

        assertThatThrownBy(() -> r.resolve(ei))
                .isInstanceOf(AutoQAException.class)
                .hasMessageContaining("All locator strategies failed");
    }

    @Test(description = "AutoQAException thrown when element has no usable locators at all")
    public void testNoUsableLocatorsThrowsException() {
        ElementInfo ei = fullElement(null, null, null, null);
        LocatorResolver r = resolver(4);

        assertThatThrownBy(() -> r.resolve(ei))
                .isInstanceOf(AutoQAException.class)
                .hasMessageContaining("All locator strategies failed");
    }

    // ── maxAttempts cap ───────────────────────────────────────────────────

    @Test(description = "maxAttempts=1 means only ID is tried even when Name/CSS/XPath are present")
    public void testMaxAttemptsLimitsStrategiesTriedToOne() {
        when(driver.findElement(By.id("a"))).thenThrow(new NoSuchElementException("no id"));
        // name would succeed if tried
        when(driver.findElement(By.name("b"))).thenReturn(mockElement);

        ElementInfo ei = fullElement("a", "b", ".c", "//d");
        LocatorResolver r = resolver(1);  // only 1 attempt allowed

        assertThatThrownBy(() -> r.resolve(ei))
                .isInstanceOf(AutoQAException.class);

        // Only ID tried; name never reached
        verify(driver, times(1)).findElement(By.id("a"));
        verify(driver, never()).findElement(By.name(any()));
    }

    @Test(description = "maxAttempts=2 tries ID then Name only")
    public void testMaxAttemptsLimitsToTwo() {
        when(driver.findElement(By.id("a"))).thenThrow(new NoSuchElementException("no id"));
        when(driver.findElement(By.name("b"))).thenThrow(new NoSuchElementException("no name"));
        // CSS would succeed if tried
        when(driver.findElement(By.cssSelector(".c"))).thenReturn(mockElement);

        ElementInfo ei = fullElement("a", "b", ".c", "//d");
        LocatorResolver r = resolver(2);

        assertThatThrownBy(() -> r.resolve(ei))
                .isInstanceOf(AutoQAException.class);

        verify(driver, times(1)).findElement(By.id("a"));
        verify(driver, times(1)).findElement(By.name("b"));
        verify(driver, never()).findElement(By.cssSelector(any()));
    }

    // ── findElement delegates correctly ──────────────────────────────────

    @Test(description = "findElement returns the WebElement located by the winning strategy")
    public void testFindElementReturnsMockedElement() {
        when(driver.findElement(By.id("my-id"))).thenReturn(mockElement);

        ElementInfo ei = fullElement("my-id", null, null, null);
        LocatorResolver r = resolver(4);

        WebElement result = r.findElement(ei);

        assertThat(result).isSameAs(mockElement);
    }

    // ── Blank vs null fields ──────────────────────────────────────────────

    @Test(description = "Blank string ID is treated as absent — falls through to next strategy")
    public void testBlankIdSkippedFallsToName() {
        when(driver.findElement(By.name("validName"))).thenReturn(mockElement);

        ElementInfo ei = fullElement("   ", "validName", null, null);
        LocatorResolver r = resolver(4);

        ElementLocator result = r.resolve(ei);

        assertThat(result.getStrategy()).isEqualTo(Strategy.NAME);
        verify(driver, never()).findElement(By.id(any()));
    }
}
