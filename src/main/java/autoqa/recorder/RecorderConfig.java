package autoqa.recorder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reads recorder settings from {@code config.properties} (classpath) and
 * exposes typed accessor methods with sensible defaults.
 *
 * <p>An optional {@code config.local.properties} file on the classpath
 * overrides any value from the base file (higher priority; not committed to VCS).
 *
 * <h3>Supported keys and defaults</h3>
 * <table>
 *   <tr><th>Key</th><th>Default</th><th>Description</th></tr>
 *   <tr><td>recorder.output.dir</td><td>recordings</td><td>Output directory</td></tr>
 *   <tr><td>recorder.session.prefix</td><td>recording</td><td>File-name prefix</td></tr>
 *   <tr><td>recorder.redact.types</td><td>password</td><td>Comma-separated element types to redact</td></tr>
 *   <tr><td>recorder.redact.selectors</td><td>(empty)</td><td>Comma-separated CSS selector substrings to redact</td></tr>
 *   <tr><td>recorder.cdp.port</td><td>9222</td><td>Edge remote-debugging port</td></tr>
 *   <tr><td>recorder.cdp.ws.timeout.sec</td><td>10</td><td>CDP WebSocket connect/command timeout</td></tr>
 *   <tr><td>recorder.url.whitelist</td><td>(empty)</td><td>Comma-separated URL prefixes to allow (empty = all)</td></tr>
 * </table>
 */
public class RecorderConfig {

    private static final Logger log = LoggerFactory.getLogger(RecorderConfig.class);

    private static final String CONFIG_FILE       = "config.properties";
    private static final String CONFIG_LOCAL_FILE = "config.local.properties";

    // Property keys
    private static final String KEY_OUTPUT_DIR        = "recorder.output.dir";
    private static final String KEY_SESSION_PREFIX    = "recorder.session.prefix";
    private static final String KEY_REDACT_TYPES      = "recorder.redact.types";
    private static final String KEY_REDACT_SELECTORS  = "recorder.redact.selectors";
    private static final String KEY_CDP_PORT          = "recorder.cdp.port";
    private static final String KEY_CDP_WS_TIMEOUT    = "recorder.cdp.ws.timeout.sec";
    private static final String KEY_URL_WHITELIST     = "recorder.url.whitelist";

    // Defaults
    private static final String  DEFAULT_OUTPUT_DIR      = "recordings";
    private static final String  DEFAULT_SESSION_PREFIX  = "recording";
    private static final String  DEFAULT_REDACT_TYPES    = "password";
    private static final String  DEFAULT_REDACT_SELECTORS = "";
    private static final int     DEFAULT_CDP_PORT        = 9222;
    private static final int     DEFAULT_CDP_WS_TIMEOUT  = 10;
    private static final String  DEFAULT_URL_WHITELIST   = "";

    private final Properties props;

    // ── Construction ──────────────────────────────────────────────────────

    /**
     * Loads configuration from the classpath.
     *
     * <p>{@code config.properties} is required. {@code config.local.properties}
     * is optional; if present its values override the base file.
     *
     * @throws RuntimeException if the base {@code config.properties} cannot be loaded
     */
    public RecorderConfig() {
        props = new Properties();
        loadBase();
        loadLocalOverrides();
    }

    /**
     * Package-private constructor for tests — accepts a pre-populated
     * {@link Properties} instance, bypassing classpath I/O.
     *
     * @param props pre-populated property set
     */
    RecorderConfig(Properties props) {
        this.props = props;
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    /**
     * Directory where recording JSON files are written.
     * Default: {@code "recordings"}.
     */
    public String getOutputDir() {
        return props.getProperty(KEY_OUTPUT_DIR, DEFAULT_OUTPUT_DIR).trim();
    }

    /**
     * Filename prefix for recording JSON files.
     * Default: {@code "recording"}.
     */
    public String getSessionPrefix() {
        return props.getProperty(KEY_SESSION_PREFIX, DEFAULT_SESSION_PREFIX).trim();
    }

    /**
     * Set of HTML input {@code type} attribute values whose text content
     * should be redacted before persisting (e.g., {@code "password"}).
     * Compared case-insensitively.
     * Default: {@code {"password"}}.
     */
    public Set<String> getRedactTypes() {
        return splitToSet(props.getProperty(KEY_REDACT_TYPES, DEFAULT_REDACT_TYPES));
    }

    /**
     * Set of CSS selector substrings — events targeting elements whose CSS
     * selector contains any of these strings will have their input data redacted.
     * Default: empty set (no selector-based redaction).
     */
    public Set<String> getRedactSelectors() {
        return splitToSet(props.getProperty(KEY_REDACT_SELECTORS, DEFAULT_REDACT_SELECTORS));
    }

    /**
     * Edge remote-debugging port to connect to via CDP.
     * Default: {@code 9222}.
     */
    public int getCdpPort() {
        return getInt(KEY_CDP_PORT, DEFAULT_CDP_PORT);
    }

    /**
     * Overrides the CDP port at runtime (e.g., from the {@code --port} CLI option).
     * Call after construction to take precedence over the config-file value.
     *
     * @param port the Edge remote-debug port to connect to
     */
    public void setCdpPort(int port) {
        props.setProperty(KEY_CDP_PORT, String.valueOf(port));
    }

    /**
     * CDP WebSocket connection and command timeout in seconds.
     * Default: {@code 10}.
     */
    public int getCdpWsTimeoutSec() {
        return getInt(KEY_CDP_WS_TIMEOUT, DEFAULT_CDP_WS_TIMEOUT);
    }

    /**
     * URL prefix whitelist for limiting which pages are recorded.
     * An empty list means all URLs are allowed.
     * Default: empty list.
     */
    public List<String> getUrlWhitelist() {
        String raw = props.getProperty(KEY_URL_WHITELIST, DEFAULT_URL_WHITELIST);
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private void loadBase() {
        try (InputStream base = getClass().getClassLoader()
                .getResourceAsStream(CONFIG_FILE)) {
            if (base == null) {
                // Base file missing is tolerated — all defaults apply
                log.warn("Classpath resource not found: {} — all recorder settings use defaults",
                        CONFIG_FILE);
                return;
            }
            props.load(base);
            log.debug("Loaded recorder base config from {}", CONFIG_FILE);
        } catch (IOException e) {
            throw new RuntimeException("Cannot load " + CONFIG_FILE, e);
        }
    }

    private void loadLocalOverrides() {
        try (InputStream local = getClass().getClassLoader()
                .getResourceAsStream(CONFIG_LOCAL_FILE)) {
            if (local != null) {
                props.load(local);
                log.debug("Applied local overrides from {}", CONFIG_LOCAL_FILE);
            }
        } catch (IOException e) {
            log.warn("Failed to read {} — using base config only: {}",
                    CONFIG_LOCAL_FILE, e.getMessage());
        }
    }

    private int getInt(String key, int defaultValue) {
        String raw = props.getProperty(key);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid integer for key '{}': '{}' — using default {}",
                    key, raw, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Splits a comma-separated string into a trimmed, lower-cased Set.
     * Empty tokens are discarded.
     */
    private static Set<String> splitToSet(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();
        for (String token : csv.split(",")) {
            String trimmed = token.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return Collections.unmodifiableSet(result);
    }
}
