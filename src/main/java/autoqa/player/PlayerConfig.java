package autoqa.player;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Reads {@code config.properties} from the classpath and exposes typed player
 * configuration values with sensible defaults.
 *
 * <p>All values can be overridden by placing a {@code config.local.properties}
 * file on the classpath (higher priority, not committed to VCS).
 */
public class PlayerConfig {

    private static final Logger log = LoggerFactory.getLogger(PlayerConfig.class);

    private static final String CONFIG_FILE       = "config.properties";
    private static final String CONFIG_LOCAL_FILE = "config.local.properties";

    // Property keys
    private static final String KEY_EXPLICIT_WAIT      = "player.explicit.wait.sec";
    private static final String KEY_PAGE_LOAD_TIMEOUT  = "player.page.load.timeout.sec";
    private static final String KEY_STEP_DELAY         = "player.step.delay.ms";
    private static final String KEY_EVIDENCE_DIR       = "player.evidence.dir";
    private static final String KEY_FALLBACK_ATTEMPTS  = "player.locator.fallback.attempts";
    private static final String KEY_SCREENSHOT_FAIL    = "player.screenshot.on.failure";
    private static final String KEY_PAGE_SOURCE_FAIL   = "player.page.source.on.failure";
    private static final String KEY_CONSOLE_LOGS_FAIL  = "player.console.logs.on.failure";
    private static final String KEY_HEALING_ENABLED    = "player.healing.enabled";

    // Defaults
    private static final int     DEFAULT_EXPLICIT_WAIT     = 15;
    private static final int     DEFAULT_PAGE_LOAD_TIMEOUT = 30;
    private static final long    DEFAULT_STEP_DELAY        = 300L;
    private static final String  DEFAULT_EVIDENCE_DIR      = "evidence";
    private static final int     DEFAULT_FALLBACK_ATTEMPTS = 3;
    private static final boolean DEFAULT_SCREENSHOT_FAIL   = true;
    private static final boolean DEFAULT_PAGE_SOURCE_FAIL  = true;
    private static final boolean DEFAULT_CONSOLE_LOGS_FAIL = true;
    private static final boolean DEFAULT_HEALING_ENABLED   = false;

    private final Properties props;

    /**
     * Loads configuration from the classpath.
     * {@code config.local.properties} values override {@code config.properties}.
     *
     * @throws RuntimeException if the base config.properties cannot be loaded
     */
    public PlayerConfig() {
        props = new Properties();

        // Load base config — required
        try (InputStream base = getClass().getClassLoader()
                .getResourceAsStream(CONFIG_FILE)) {
            if (base == null) {
                throw new IOException("Classpath resource not found: " + CONFIG_FILE);
            }
            props.load(base);
            log.debug("Loaded base config from {}", CONFIG_FILE);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load " + CONFIG_FILE, e);
        }

        // Load local overrides — optional, no error if missing
        try (InputStream local = getClass().getClassLoader()
                .getResourceAsStream(CONFIG_LOCAL_FILE)) {
            if (local != null) {
                props.load(local);
                log.debug("Applied local overrides from {}", CONFIG_LOCAL_FILE);
            }
        } catch (IOException e) {
            log.warn("Failed to read {} — using base config only: {}", CONFIG_LOCAL_FILE, e.getMessage());
        }
    }

    /**
     * Package-private constructor for tests — accepts an already-populated
     * {@link Properties} instance.
     */
    PlayerConfig(Properties props) {
        this.props = props;
    }

    // ── Accessors ──────────────────────────────────────────────────────────

    /** Explicit WebDriverWait timeout in seconds (default: 15). */
    public int getExplicitWaitSec() {
        return getInt(KEY_EXPLICIT_WAIT, DEFAULT_EXPLICIT_WAIT);
    }

    /** Page-load timeout in seconds (default: 30). */
    public int getPageLoadTimeoutSec() {
        return getInt(KEY_PAGE_LOAD_TIMEOUT, DEFAULT_PAGE_LOAD_TIMEOUT);
    }

    /** Delay between replayed steps in milliseconds (default: 300). */
    public long getStepDelayMs() {
        return getLong(KEY_STEP_DELAY, DEFAULT_STEP_DELAY);
    }

    /** Directory where evidence artifacts are written (default: "evidence"). */
    public String getEvidenceDir() {
        return props.getProperty(KEY_EVIDENCE_DIR, DEFAULT_EVIDENCE_DIR).trim();
    }

    /**
     * Number of locator strategies to try before invoking AI healing
     * (default: 3).
     */
    public int getLocatorFallbackAttempts() {
        return getInt(KEY_FALLBACK_ATTEMPTS, DEFAULT_FALLBACK_ATTEMPTS);
    }

    /** Whether to capture a screenshot on step failure (default: true). */
    public boolean isScreenshotOnFailure() {
        return getBool(KEY_SCREENSHOT_FAIL, DEFAULT_SCREENSHOT_FAIL);
    }

    /** Whether to capture the page source on step failure (default: true). */
    public boolean isPageSourceOnFailure() {
        return getBool(KEY_PAGE_SOURCE_FAIL, DEFAULT_PAGE_SOURCE_FAIL);
    }

    /** Whether to capture browser console logs on step failure (default: true). */
    public boolean isConsoleLogsOnFailure() {
        return getBool(KEY_CONSOLE_LOGS_FAIL, DEFAULT_CONSOLE_LOGS_FAIL);
    }

    /** Whether AI self-healing via {@code HealingInterceptor} is enabled (default: false). */
    public boolean isHealingEnabled() {
        return getBool(KEY_HEALING_ENABLED, DEFAULT_HEALING_ENABLED);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private int getInt(String key, int defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid integer for key '{}': '{}' — using default {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    private long getLong(String key, long defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid long for key '{}': '{}' — using default {}", key, raw, defaultValue);
            return defaultValue;
        }
    }

    private boolean getBool(String key, boolean defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        return Boolean.parseBoolean(raw.trim());
    }
}
