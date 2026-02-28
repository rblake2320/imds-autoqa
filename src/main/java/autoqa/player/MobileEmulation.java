package autoqa.player;

import org.openqa.selenium.Dimension;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.ChromiumDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Mobile device emulation for Edge and Chrome using Chrome DevTools Protocol (CDP).
 *
 * <p>Emulates a mobile device by overriding viewport, device pixel ratio, User-Agent,
 * and touch event settings via CDP raw commands (compatible with all Selenium 4 versions).
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * MobileEmulation.apply(driver, MobileEmulation.Device.IPHONE_14);
 * driver.get("https://example.com");
 * // ... run mobile test steps ...
 * MobileEmulation.reset(driver);
 * }</pre>
 *
 * <p>Requires an Edge or Chrome {@link ChromiumDriver}.
 */
public class MobileEmulation {

    private static final Logger log = LoggerFactory.getLogger(MobileEmulation.class);

    /**
     * Preset mobile device profiles.
     * Dimensions and DPR match Chrome DevTools device emulation catalogue.
     */
    public enum Device {
        IPHONE_SE        ("iPhone SE",          375, 667,  2.0, "Mozilla/5.0 (iPhone; CPU iPhone OS 15_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Mobile/15E148 Safari/604.1"),
        IPHONE_14        ("iPhone 14",           390, 844,  3.0, "Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1"),
        IPHONE_14_PRO_MAX("iPhone 14 Pro Max",  430, 932,  3.0, "Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1"),
        PIXEL_7          ("Pixel 7",             412, 915,  2.625, "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"),
        PIXEL_7_PRO      ("Pixel 7 Pro",         412, 892,  3.5, "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"),
        SAMSUNG_S23      ("Samsung Galaxy S23",  360, 780,  4.0, "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"),
        IPAD_AIR         ("iPad Air",            820, 1180, 2.0, "Mozilla/5.0 (iPad; CPU OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1"),
        IPAD_PRO_12      ("iPad Pro 12.9",      1024, 1366, 2.0, "Mozilla/5.0 (iPad; CPU OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1"),
        GALAXY_TAB_S8    ("Galaxy Tab S8",       800, 1280, 2.0, "Mozilla/5.0 (Linux; Android 12; SM-X706B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"),
        SURFACE_PRO_7    ("Surface Pro 7",       912, 1368, 2.0, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36");

        public final String  displayName;
        public final int     width;
        public final int     height;
        public final double  devicePixelRatio;
        public final String  userAgent;

        Device(String name, int w, int h, double dpr, String ua) {
            this.displayName      = name;
            this.width            = w;
            this.height           = h;
            this.devicePixelRatio = dpr;
            this.userAgent        = ua;
        }
    }

    private MobileEmulation() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Applies mobile device emulation using a preset {@link Device}.
     *
     * @param driver a {@link ChromiumDriver} (Edge or Chrome)
     * @param device the device preset to emulate
     */
    public static void apply(WebDriver driver, Device device) {
        apply(driver, device.width, device.height, device.devicePixelRatio, device.userAgent, true);
        log.info("MobileEmulation: applied {} ({}×{} @{}x DPR)",
                device.displayName, device.width, device.height, device.devicePixelRatio);
    }

    /**
     * Applies custom mobile emulation settings.
     *
     * @param driver      a {@link ChromiumDriver}
     * @param width       viewport width in CSS pixels
     * @param height      viewport height in CSS pixels
     * @param dpr         device pixel ratio
     * @param userAgent   custom User-Agent string (null to keep default)
     * @param isMobile    whether to set the mobile metadata flag
     */
    public static void apply(WebDriver driver, int width, int height,
                              double dpr, String userAgent, boolean isMobile) {
        if (driver instanceof ChromiumDriver chromium) {
            applyViaCdp(chromium, width, height, dpr, userAgent, isMobile);
        } else {
            log.warn("MobileEmulation: not a ChromiumDriver — window resize only");
            driver.manage().window().setSize(new Dimension(width, height));
        }
    }

    /**
     * Resets all emulation settings to desktop mode.
     *
     * @param driver a {@link ChromiumDriver}
     */
    public static void reset(WebDriver driver) {
        if (driver instanceof ChromiumDriver chromium) {
            try {
                chromium.executeCdpCommand("Emulation.clearDeviceMetricsOverride", Map.of());
                log.info("MobileEmulation: reset to desktop mode");
            } catch (Exception e) {
                log.warn("MobileEmulation: reset failed — {}", e.getMessage());
                driver.manage().window().maximize();
            }
        } else {
            driver.manage().window().maximize();
        }
    }

    // ── CDP implementation ────────────────────────────────────────────────────

    /**
     * Applies emulation using raw CDP commands (compatible with all Selenium 4 versions,
     * avoids version-specific typed DevTools API).
     */
    private static void applyViaCdp(ChromiumDriver driver, int width, int height,
                                     double dpr, String userAgent, boolean isMobile) {
        // Set device metrics
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("width",             width);
        metrics.put("height",            height);
        metrics.put("deviceScaleFactor", dpr);
        metrics.put("mobile",            isMobile);
        driver.executeCdpCommand("Emulation.setDeviceMetricsOverride", metrics);

        // Set User-Agent
        if (userAgent != null && !userAgent.isBlank()) {
            Map<String, Object> ua = new HashMap<>();
            ua.put("userAgent", userAgent);
            driver.executeCdpCommand("Emulation.setUserAgentOverride", ua);
        }

        // Enable touch emulation
        if (isMobile) {
            Map<String, Object> touch = new HashMap<>();
            touch.put("enabled",       true);
            touch.put("maxTouchPoints", 5);
            driver.executeCdpCommand("Emulation.setTouchEmulationEnabled", touch);
        }

        log.debug("MobileEmulation: CDP applied {}×{} @{}x DPR mobile={}", width, height, dpr, isMobile);
    }
}
