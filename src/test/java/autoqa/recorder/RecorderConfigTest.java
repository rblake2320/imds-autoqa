package autoqa.recorder;

import org.testng.annotations.Test;

import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link RecorderConfig}.
 *
 * <p>Uses the package-private {@code RecorderConfig(Properties)} constructor
 * to inject property values without touching the classpath, keeping tests
 * fast and environment-independent.
 */
public class RecorderConfigTest {

    // ── Default value tests ───────────────────────────────────────────────

    @Test(description = "All accessors return documented defaults when properties are empty")
    public void testAllDefaults() {
        RecorderConfig cfg = new RecorderConfig(new Properties());

        assertThat(cfg.getOutputDir())
                .as("outputDir default")
                .isEqualTo("recordings");

        assertThat(cfg.getSessionPrefix())
                .as("sessionPrefix default")
                .isEqualTo("recording");

        assertThat(cfg.getRedactTypes())
                .as("redactTypes default must contain 'password'")
                .containsExactly("password");

        assertThat(cfg.getRedactSelectors())
                .as("redactSelectors default must be empty")
                .isEmpty();

        assertThat(cfg.getCdpPort())
                .as("cdpPort default")
                .isEqualTo(9222);

        assertThat(cfg.getCdpWsTimeoutSec())
                .as("cdpWsTimeoutSec default")
                .isEqualTo(10);

        assertThat(cfg.getUrlWhitelist())
                .as("urlWhitelist default must be empty")
                .isEmpty();
    }

    // ── String property overrides ─────────────────────────────────────────

    @Test(description = "outputDir is read from properties and trimmed")
    public void testOutputDirOverride() {
        Properties p = new Properties();
        p.setProperty("recorder.output.dir", "  /var/autoqa/recordings  ");

        RecorderConfig cfg = new RecorderConfig(p);

        assertThat(cfg.getOutputDir()).isEqualTo("/var/autoqa/recordings");
    }

    @Test(description = "sessionPrefix is read from properties and trimmed")
    public void testSessionPrefixOverride() {
        Properties p = new Properties();
        p.setProperty("recorder.session.prefix", "  imds-session  ");

        RecorderConfig cfg = new RecorderConfig(p);

        assertThat(cfg.getSessionPrefix()).isEqualTo("imds-session");
    }

    // ── Integer property overrides ────────────────────────────────────────

    @Test(description = "cdpPort is read from properties correctly")
    public void testCdpPortOverride() {
        Properties p = new Properties();
        p.setProperty("recorder.cdp.port", "9223");

        RecorderConfig cfg = new RecorderConfig(p);

        assertThat(cfg.getCdpPort()).isEqualTo(9223);
    }

    @Test(description = "cdpWsTimeoutSec is read from properties correctly")
    public void testCdpWsTimeoutOverride() {
        Properties p = new Properties();
        p.setProperty("recorder.cdp.ws.timeout.sec", "30");

        RecorderConfig cfg = new RecorderConfig(p);

        assertThat(cfg.getCdpWsTimeoutSec()).isEqualTo(30);
    }

    @Test(description = "Invalid integer value for cdpPort falls back to default")
    public void testInvalidIntegerFallsBackToDefault() {
        Properties p = new Properties();
        p.setProperty("recorder.cdp.port", "not-a-number");

        RecorderConfig cfg = new RecorderConfig(p);

        // Must not throw; must return default
        assertThat(cfg.getCdpPort()).isEqualTo(9222);
    }

    @Test(description = "Blank integer string for cdpWsTimeoutSec falls back to default")
    public void testBlankIntegerFallsBackToDefault() {
        Properties p = new Properties();
        p.setProperty("recorder.cdp.ws.timeout.sec", "   ");

        RecorderConfig cfg = new RecorderConfig(p);

        assertThat(cfg.getCdpWsTimeoutSec()).isEqualTo(10);
    }

    // ── Set property overrides (redact types / selectors) ─────────────────

    @Test(description = "redactTypes is parsed from comma-separated string, lowercased")
    public void testRedactTypesOverride() {
        Properties p = new Properties();
        p.setProperty("recorder.redact.types", "PASSWORD, Hidden , tel");

        RecorderConfig cfg = new RecorderConfig(p);
        Set<String> types = cfg.getRedactTypes();

        assertThat(types)
                .as("redactTypes must be lower-cased and trimmed")
                .contains("password", "hidden", "tel")
                .hasSize(3);
    }

    @Test(description = "redactSelectors is parsed from comma-separated string")
    public void testRedactSelectorsOverride() {
        Properties p = new Properties();
        p.setProperty("recorder.redact.selectors", ".password-field, #ssn, .secret");

        RecorderConfig cfg = new RecorderConfig(p);
        Set<String> selectors = cfg.getRedactSelectors();

        assertThat(selectors)
                .contains(".password-field", "#ssn", ".secret")
                .hasSize(3);
    }

    @Test(description = "redactTypes empty string results in empty set")
    public void testRedactTypesEmptyString() {
        Properties p = new Properties();
        p.setProperty("recorder.redact.types", "");

        RecorderConfig cfg = new RecorderConfig(p);

        assertThat(cfg.getRedactTypes()).isEmpty();
    }

    @Test(description = "redactSelectors handles blank-only string gracefully")
    public void testRedactSelectorsBlankString() {
        Properties p = new Properties();
        p.setProperty("recorder.redact.selectors", "   ");

        RecorderConfig cfg = new RecorderConfig(p);

        assertThat(cfg.getRedactSelectors()).isEmpty();
    }

    @Test(description = "redactTypes deduplicates identical entries")
    public void testRedactTypesDeduplicated() {
        Properties p = new Properties();
        p.setProperty("recorder.redact.types", "password,password,PASSWORD");

        RecorderConfig cfg = new RecorderConfig(p);

        // All three normalise to "password"; the set must contain only one entry
        assertThat(cfg.getRedactTypes())
                .as("Duplicate redact types must be de-duplicated")
                .containsExactly("password")
                .hasSize(1);
    }

    // ── URL whitelist ─────────────────────────────────────────────────────

    @Test(description = "urlWhitelist is parsed from comma-separated string")
    public void testUrlWhitelistOverride() {
        Properties p = new Properties();
        p.setProperty("recorder.url.whitelist", "https://imds.example.com, https://staging.imds.example.com");

        RecorderConfig cfg = new RecorderConfig(p);
        List<String> whitelist = cfg.getUrlWhitelist();

        assertThat(whitelist)
                .containsExactly("https://imds.example.com", "https://staging.imds.example.com");
    }

    @Test(description = "urlWhitelist is empty when property is not set")
    public void testUrlWhitelistDefaultEmpty() {
        RecorderConfig cfg = new RecorderConfig(new Properties());

        assertThat(cfg.getUrlWhitelist()).isEmpty();
    }

    @Test(description = "urlWhitelist is empty when property is blank")
    public void testUrlWhitelistBlankIsEmpty() {
        Properties p = new Properties();
        p.setProperty("recorder.url.whitelist", "   ");

        RecorderConfig cfg = new RecorderConfig(p);

        assertThat(cfg.getUrlWhitelist()).isEmpty();
    }

    @Test(description = "urlWhitelist entries are trimmed")
    public void testUrlWhitelistTrimmed() {
        Properties p = new Properties();
        p.setProperty("recorder.url.whitelist", "  https://a.com  ,  https://b.com  ");

        RecorderConfig cfg = new RecorderConfig(p);

        assertThat(cfg.getUrlWhitelist())
                .containsExactly("https://a.com", "https://b.com");
    }

    // ── Return-type contracts ─────────────────────────────────────────────

    @Test(description = "getRedactTypes returns an unmodifiable set")
    public void testRedactTypesIsUnmodifiable() {
        RecorderConfig cfg = new RecorderConfig(new Properties());

        assertThatThrownBy(() -> cfg.getRedactTypes().add("newtype"))
                .as("getRedactTypes() must return an unmodifiable set")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test(description = "getRedactSelectors returns an unmodifiable set")
    public void testRedactSelectorsIsUnmodifiable() {
        Properties p = new Properties();
        p.setProperty("recorder.redact.selectors", ".some-selector");
        RecorderConfig cfg = new RecorderConfig(p);

        assertThatThrownBy(() -> cfg.getRedactSelectors().add("extra"))
                .as("getRedactSelectors() must return an unmodifiable set")
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test(description = "Classpath config.properties loads without error when present")
    public void testClasspathConfigLoadsWithoutError() {
        // Exercises the real file-loading constructor path.
        // config.properties may be absent on the test classpath — both outcomes are valid.
        try {
            RecorderConfig cfg = new RecorderConfig();
            // Sanity-check that all accessors return reasonable values
            assertThat(cfg.getOutputDir()).isNotBlank();
            assertThat(cfg.getSessionPrefix()).isNotBlank();
            assertThat(cfg.getCdpPort()).isGreaterThan(0);
            assertThat(cfg.getCdpWsTimeoutSec()).isGreaterThan(0);
        } catch (RuntimeException e) {
            // config.properties absent on classpath — that is acceptable in the test environment
            // The important thing is that the constructor handles missing files gracefully
            // (it logs a warning rather than crashing with NPE).
            assertThat(e.getMessage())
                    .as("If config.properties cannot be loaded, the error message must name the file")
                    .contains("config.properties");
        }
    }
}
