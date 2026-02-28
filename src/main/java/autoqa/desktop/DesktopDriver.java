package autoqa.desktop;

import autoqa.player.AutoQAException;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.util.NoSuchElementException;

/**
 * Desktop application testing via WinAppDriver (Windows Application Driver).
 *
 * <p>Equivalent to UFT One's WPF / Win32 / UWP desktop testing.
 * WinAppDriver exposes a WebDriver-compatible endpoint at
 * {@code http://127.0.0.1:4723/wd/hub} that drives Windows UI Automation.
 *
 * <h3>Prerequisites</h3>
 * <ol>
 *   <li>Install WinAppDriver from
 *       https://github.com/microsoft/WinAppDriver/releases</li>
 *   <li>Enable Developer Mode in Windows Settings → Developer</li>
 *   <li>Start WinAppDriver.exe (runs on port 4723 by default)</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * DesktopDriver desktop = DesktopDriver.launchApp("C:/Windows/System32/calc.exe");
 * desktop.click(By.name("One"));
 * desktop.typeText(By.name("equals"), "");
 * desktop.quit();
 * }</pre>
 *
 * <h3>UFT Parity</h3>
 * <ul>
 *   <li>Launch desktop application by path</li>
 *   <li>Attach to already-running application by process name</li>
 *   <li>Click, double-click, right-click controls</li>
 *   <li>Type text into controls</li>
 *   <li>Read control text / value</li>
 *   <li>Control existence / visibility verification</li>
 * </ul>
 */
public class DesktopDriver {

    private static final Logger log = LoggerFactory.getLogger(DesktopDriver.class);

    /** Default WinAppDriver endpoint. Override via constructor. */
    public static final String DEFAULT_WAD_URL = "http://127.0.0.1:4723/wd/hub";

    /**
     * Automation name used for WinAppDriver sessions.
     * WinAppDriver ignores this but it documents intent.
     */
    private static final String AUTOMATION_NAME = "Windows";

    private final WebDriver session;

    // ── Factories ─────────────────────────────────────────────────────────────

    /**
     * Launches a Windows application and returns a driver connected to it.
     *
     * @param appPath full path to the executable (or "Root" to connect to the
     *                Windows desktop root session for attaching to other apps)
     * @throws AutoQAException if WinAppDriver is not running or the app fails to launch
     */
    public static DesktopDriver launchApp(String appPath) {
        return launchApp(appPath, DEFAULT_WAD_URL);
    }

    /**
     * Launches a Windows application against a custom WinAppDriver URL.
     *
     * @param appPath    full path to the executable
     * @param wadBaseUrl WinAppDriver base URL (e.g. {@code "http://localhost:4723/wd/hub"})
     */
    public static DesktopDriver launchApp(String appPath, String wadBaseUrl) {
        log.info("Launching desktop app: {}", appPath);
        DesiredCapabilities caps = new DesiredCapabilities();
        caps.setCapability("app", appPath);
        caps.setCapability("platformName", "Windows");
        caps.setCapability("automationName", AUTOMATION_NAME);
        return new DesktopDriver(wadBaseUrl, caps);
    }

    /**
     * Attaches to an already-running application by its top-level window title.
     *
     * @param windowTitle partial or full window title
     */
    public static DesktopDriver attachByTitle(String windowTitle) {
        return attachByTitle(windowTitle, DEFAULT_WAD_URL);
    }

    /**
     * Attaches to an already-running application by window title.
     *
     * @param windowTitle window title substring
     * @param wadBaseUrl  WinAppDriver URL
     */
    public static DesktopDriver attachByTitle(String windowTitle, String wadBaseUrl) {
        log.info("Attaching to desktop window: '{}'", windowTitle);
        // Connect to root desktop first, then find window by name
        DesiredCapabilities rootCaps = new DesiredCapabilities();
        rootCaps.setCapability("app", "Root");
        rootCaps.setCapability("platformName", "Windows");
        rootCaps.setCapability("automationName", AUTOMATION_NAME);

        DesktopDriver root = new DesktopDriver(wadBaseUrl, rootCaps);
        try {
            WebElement window = root.find(By.name(windowTitle));
            String appSessionId = window.getAttribute("NativeWindowHandle");

            DesiredCapabilities appCaps = new DesiredCapabilities();
            appCaps.setCapability("appTopLevelWindow",
                    "0x" + Integer.toHexString(Integer.parseInt(appSessionId)));
            appCaps.setCapability("platformName", "Windows");
            appCaps.setCapability("automationName", AUTOMATION_NAME);
            root.quit();
            return new DesktopDriver(wadBaseUrl, appCaps);
        } catch (Exception e) {
            root.quit();
            throw new AutoQAException("Could not attach to window '" + windowTitle + "': " + e.getMessage(), e);
        }
    }

    private DesktopDriver(String wadBaseUrl, DesiredCapabilities caps) {
        try {
            this.session = new RemoteWebDriver(new URL(wadBaseUrl), caps);
            this.session.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
            log.info("WinAppDriver session created");
        } catch (MalformedURLException e) {
            throw new AutoQAException("Invalid WinAppDriver URL: " + wadBaseUrl, e);
        } catch (Exception e) {
            throw new AutoQAException("Failed to create WinAppDriver session. "
                    + "Is WinAppDriver.exe running at " + wadBaseUrl + "? Error: " + e.getMessage(), e);
        }
    }

    // ── Interactions ──────────────────────────────────────────────────────────

    /** Finds a control by locator (name, automationId, className, xpath). */
    public WebElement find(By by) {
        return session.findElement(by);
    }

    /** Finds by Windows Automation ID (preferred, stable across renames). */
    public WebElement findById(String automationId) {
        return find(By.id(automationId));
    }

    /** Finds by control name (display text). */
    public WebElement findByName(String name) {
        return find(By.name(name));
    }

    /** Finds by ClassName (WPF/WinForms control class). */
    public WebElement findByClass(String className) {
        return find(By.className(className));
    }

    /** Clicks a control. */
    public void click(By by) {
        log.debug("Desktop click: {}", by);
        find(by).click();
    }

    /** Double-clicks a control. */
    public void doubleClick(By by) {
        log.debug("Desktop double-click: {}", by);
        new org.openqa.selenium.interactions.Actions(session)
                .doubleClick(find(by)).perform();
    }

    /** Types text into a control (clear + sendKeys). */
    public void typeText(By by, String text) {
        log.debug("Desktop type '{}' into {}", text, by);
        WebElement el = find(by);
        el.clear();
        el.sendKeys(text);
    }

    /** Reads the text / value of a control. */
    public String getText(By by) {
        return find(by).getText();
    }

    /** Returns true if the control exists and is displayed. */
    public boolean isVisible(By by) {
        try {
            return find(by).isDisplayed();
        } catch (NoSuchElementException e) {
            return false;
        }
    }

    /** Returns true if the control exists in the UI tree. */
    public boolean exists(By by) {
        try {
            find(by);
            return true;
        } catch (NoSuchElementException e) {
            return false;
        }
    }

    /** Reads a control attribute (e.g. "Value.Value" for a text box value). */
    public String getAttribute(By by, String attributeName) {
        return find(by).getAttribute(attributeName);
    }

    // ── Session lifecycle ─────────────────────────────────────────────────────

    /** Closes the application and ends the WinAppDriver session. */
    public void quit() {
        try {
            if (session != null) session.quit();
            log.info("WinAppDriver session closed");
        } catch (Exception e) {
            log.warn("Error closing WinAppDriver session: {}", e.getMessage());
        }
    }

    /**
     * Returns the underlying {@link WebDriver} for advanced operations
     * (screenshots, script execution, etc.).
     */
    public WebDriver getDriver() {
        return session;
    }
}
