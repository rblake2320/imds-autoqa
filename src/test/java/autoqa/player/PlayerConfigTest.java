package autoqa.player;

import org.testng.annotations.Test;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PlayerConfig}.
 *
 * <p>Tests use the package-private {@code PlayerConfig(Properties)} constructor
 * to avoid classpath file I/O and allow precise value injection.
 */
public class PlayerConfigTest {

    // ── Default value tests ───────────────────────────────────────────────

    @Test(description = "All accessor methods return documented defaults when properties are empty")
    public void testAllDefaults() {
        PlayerConfig cfg = new PlayerConfig(new Properties());

        assertThat(cfg.getExplicitWaitSec())
                .as("explicitWaitSec default")
                .isEqualTo(15);

        assertThat(cfg.getPageLoadTimeoutSec())
                .as("pageLoadTimeoutSec default")
                .isEqualTo(30);

        assertThat(cfg.getStepDelayMs())
                .as("stepDelayMs default")
                .isEqualTo(300L);

        assertThat(cfg.getEvidenceDir())
                .as("evidenceDir default")
                .isEqualTo("evidence");

        assertThat(cfg.getLocatorFallbackAttempts())
                .as("locatorFallbackAttempts default")
                .isEqualTo(3);

        assertThat(cfg.isScreenshotOnFailure())
                .as("screenshotOnFailure default")
                .isTrue();

        assertThat(cfg.isPageSourceOnFailure())
                .as("pageSourceOnFailure default")
                .isTrue();

        assertThat(cfg.isConsoleLogsOnFailure())
                .as("consoleLogsOnFailure default")
                .isTrue();
    }

    // ── Override tests ────────────────────────────────────────────────────

    @Test(description = "Integer values are read from properties correctly")
    public void testIntegerOverrides() {
        Properties p = new Properties();
        p.setProperty("player.explicit.wait.sec", "25");
        p.setProperty("player.page.load.timeout.sec", "60");
        p.setProperty("player.locator.fallback.attempts", "5");

        PlayerConfig cfg = new PlayerConfig(p);

        assertThat(cfg.getExplicitWaitSec()).isEqualTo(25);
        assertThat(cfg.getPageLoadTimeoutSec()).isEqualTo(60);
        assertThat(cfg.getLocatorFallbackAttempts()).isEqualTo(5);
    }

    @Test(description = "Long value for step delay is read correctly")
    public void testStepDelayOverride() {
        Properties p = new Properties();
        p.setProperty("player.step.delay.ms", "750");

        PlayerConfig cfg = new PlayerConfig(p);

        assertThat(cfg.getStepDelayMs()).isEqualTo(750L);
    }

    @Test(description = "String value for evidence dir is read and trimmed")
    public void testEvidenceDirOverride() {
        Properties p = new Properties();
        p.setProperty("player.evidence.dir", "  /var/autoqa/evidence  ");

        PlayerConfig cfg = new PlayerConfig(p);

        assertThat(cfg.getEvidenceDir()).isEqualTo("/var/autoqa/evidence");
    }

    @Test(description = "Boolean flags can be disabled via properties")
    public void testBooleanFlagsDisabled() {
        Properties p = new Properties();
        p.setProperty("player.screenshot.on.failure", "false");
        p.setProperty("player.page.source.on.failure", "false");
        p.setProperty("player.console.logs.on.failure", "false");

        PlayerConfig cfg = new PlayerConfig(p);

        assertThat(cfg.isScreenshotOnFailure()).isFalse();
        assertThat(cfg.isPageSourceOnFailure()).isFalse();
        assertThat(cfg.isConsoleLogsOnFailure()).isFalse();
    }

    @Test(description = "Boolean flags remain true when set to 'true'")
    public void testBooleanFlagsEnabled() {
        Properties p = new Properties();
        p.setProperty("player.screenshot.on.failure", "true");
        p.setProperty("player.page.source.on.failure", "true");
        p.setProperty("player.console.logs.on.failure", "true");

        PlayerConfig cfg = new PlayerConfig(p);

        assertThat(cfg.isScreenshotOnFailure()).isTrue();
        assertThat(cfg.isPageSourceOnFailure()).isTrue();
        assertThat(cfg.isConsoleLogsOnFailure()).isTrue();
    }

    // ── Resilience tests ──────────────────────────────────────────────────

    @Test(description = "Invalid integer value falls back to default gracefully")
    public void testInvalidIntegerFallsBackToDefault() {
        Properties p = new Properties();
        p.setProperty("player.explicit.wait.sec", "not-a-number");

        PlayerConfig cfg = new PlayerConfig(p);

        // Must not throw; must return default
        assertThat(cfg.getExplicitWaitSec()).isEqualTo(15);
    }

    @Test(description = "Invalid long value falls back to default gracefully")
    public void testInvalidLongFallsBackToDefault() {
        Properties p = new Properties();
        p.setProperty("player.step.delay.ms", "abc");

        PlayerConfig cfg = new PlayerConfig(p);

        assertThat(cfg.getStepDelayMs()).isEqualTo(300L);
    }

    @Test(description = "Blank string for integer key falls back to default")
    public void testBlankIntegerFallsBackToDefault() {
        Properties p = new Properties();
        p.setProperty("player.page.load.timeout.sec", "   ");

        PlayerConfig cfg = new PlayerConfig(p);

        assertThat(cfg.getPageLoadTimeoutSec()).isEqualTo(30);
    }

    @Test(description = "Non-boolean string for flag is treated as false")
    public void testNonBooleanStringForFlag() {
        Properties p = new Properties();
        p.setProperty("player.screenshot.on.failure", "yes");  // Boolean.parseBoolean("yes") == false

        PlayerConfig cfg = new PlayerConfig(p);

        // Java's Boolean.parseBoolean only recognises "true" (case-insensitive)
        assertThat(cfg.isScreenshotOnFailure()).isFalse();
    }

    @Test(description = "Classpath config.properties loads without error")
    public void testClasspathConfigLoads() {
        // This exercises the real file-loading constructor
        PlayerConfig cfg = new PlayerConfig();

        // All values should be within sane ranges even from the real file
        assertThat(cfg.getExplicitWaitSec()).isGreaterThan(0);
        assertThat(cfg.getPageLoadTimeoutSec()).isGreaterThan(0);
        assertThat(cfg.getStepDelayMs()).isGreaterThanOrEqualTo(0L);
        assertThat(cfg.getEvidenceDir()).isNotBlank();
        assertThat(cfg.getLocatorFallbackAttempts()).isGreaterThan(0);
    }
}
