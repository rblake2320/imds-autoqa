package autoqa.player;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chromium.HasCdp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Browser console log monitor using Chrome DevTools Protocol (CDP).
 *
 * <p>Captures JavaScript console messages (errors, warnings, logs) during test execution,
 * enabling assertions like "no console errors occurred" or "a specific warning was logged".
 *
 * <p>Requires a Chromium-based browser (Edge or Chrome).
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * ConsoleMonitor console = ConsoleMonitor.attach(driver);
 * console.start();
 *
 * driver.get("https://example.com");
 * // ... test steps ...
 *
 * console.assertNoErrors();   // throws if any console error was logged
 * console.assertNoSevere();   // throws if any "error" level message
 *
 * List<ConsoleMessage> errors = console.getErrors();
 * console.stop();
 * }</pre>
 */
public class ConsoleMonitor {

    private static final Logger log = LoggerFactory.getLogger(ConsoleMonitor.class);

    /** Browser console message level. */
    public enum Level {
        LOG, DEBUG, INFO, WARNING, ERROR, VERBOSE;

        public static Level fromString(String s) {
            if (s == null) return LOG;
            return switch (s.toLowerCase()) {
                case "error"   -> ERROR;
                case "warning", "warn" -> WARNING;
                case "info"    -> INFO;
                case "debug"   -> DEBUG;
                case "verbose" -> VERBOSE;
                default        -> LOG;
            };
        }
    }

    /** An immutable browser console message. */
    public record ConsoleMessage(Level level, String text, String url, int line, Instant timestamp) {
        public boolean isError()   { return level == Level.ERROR; }
        public boolean isWarning() { return level == Level.WARNING; }

        @Override
        public String toString() {
            return String.format("[%s] %s (at %s:%d)", level, text, url, line);
        }
    }

    private final HasCdp cdp;
    private final List<ConsoleMessage> messages = new CopyOnWriteArrayList<>();
    private boolean active = false;

    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Attaches a ConsoleMonitor to a Chromium-based WebDriver.
     *
     * @throws IllegalArgumentException if the driver does not support CDP
     */
    public static ConsoleMonitor attach(WebDriver driver) {
        if (!(driver instanceof HasCdp cdp)) {
            throw new IllegalArgumentException(
                    "ConsoleMonitor requires a Chromium-based WebDriver (Edge or Chrome). " +
                    "Got: " + driver.getClass().getSimpleName());
        }
        return new ConsoleMonitor(cdp);
    }

    /** Package-visible constructor for testing (allows null CDP). */
    ConsoleMonitor(HasCdp cdp) {
        this.cdp = cdp;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Starts console message capture. Enables the CDP Console and Log domains.
     */
    public ConsoleMonitor start() {
        messages.clear();

        // Enable the Log domain (captures console messages from all frames)
        cdp.executeCdpCommand("Log.enable", Map.of());

        // Enable the Runtime domain (for runtime exceptions)
        cdp.executeCdpCommand("Runtime.enable", Map.of());

        active = true;
        log.info("ConsoleMonitor started");
        return this;
    }

    /**
     * Stops console monitoring.
     */
    public ConsoleMonitor stop() {
        if (active) {
            try {
                cdp.executeCdpCommand("Log.disable", Map.of());
            } catch (Exception ignored) {}
            active = false;
        }
        log.info("ConsoleMonitor stopped — {} message(s) captured", messages.size());
        return this;
    }

    /** Clears captured messages without stopping the monitor. */
    public ConsoleMonitor clear() {
        messages.clear();
        return this;
    }

    // ── Manual recording ──────────────────────────────────────────────────────

    /** Manually records a console message (for testing or CDP listener callbacks). */
    public void record(Level level, String text, String url, int line) {
        messages.add(new ConsoleMessage(level, text, url != null ? url : "", line, Instant.now()));
    }

    /** Manually records a console message from a raw CDP event map. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void recordFromCdpEvent(Map<String, Object> event) {
        // CDP Log.entryAdded event structure
        Object entry = event.get("entry");
        if (entry instanceof Map rawMap) {
            Map<Object, Object> e = rawMap;
            String levelStr = (String) e.get("level");
            Object textObj  = e.get("text");
            Object urlObj   = e.get("url");
            String text     = textObj != null ? textObj.toString() : "";
            String url      = urlObj  != null ? urlObj.toString()  : "";
            Object lineRaw  = e.get("lineNumber");
            int line        = lineRaw instanceof Number n ? n.intValue() : 0;
            record(Level.fromString(levelStr), text, url, line);
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** All captured messages. */
    public List<ConsoleMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    /** All captured error messages. */
    public List<ConsoleMessage> getErrors() {
        return messages.stream().filter(ConsoleMessage::isError).toList();
    }

    /** All captured warning messages. */
    public List<ConsoleMessage> getWarnings() {
        return messages.stream().filter(ConsoleMessage::isWarning).toList();
    }

    /** Messages at or above the specified severity level. */
    public List<ConsoleMessage> atLevel(Level minLevel) {
        return messages.stream().filter(m -> m.level().ordinal() >= minLevel.ordinal()).toList();
    }

    /** Messages whose text contains the given substring. */
    public List<ConsoleMessage> containing(String text) {
        return messages.stream().filter(m -> m.text().contains(text)).toList();
    }

    // ── Assertions ─────────────────────────────────────────────────────────────

    /**
     * Asserts that no console error messages were logged.
     *
     * @throws AssertionError if any console error was captured
     */
    public ConsoleMonitor assertNoErrors() {
        List<ConsoleMessage> errors = getErrors();
        if (!errors.isEmpty()) {
            String detail = errors.stream()
                    .map(ConsoleMessage::toString)
                    .reduce("", (a, b) -> a + "\n  " + b);
            throw new AssertionError(
                    "ConsoleMonitor: " + errors.size() + " console error(s) detected:" + detail);
        }
        log.info("ConsoleMonitor ✓ no console errors");
        return this;
    }

    /**
     * Asserts that no console warnings or errors were logged.
     *
     * @throws AssertionError if any warning or error was captured
     */
    public ConsoleMonitor assertNoWarningsOrErrors() {
        List<ConsoleMessage> severe = atLevel(Level.WARNING);
        if (!severe.isEmpty()) {
            throw new AssertionError(
                    "ConsoleMonitor: " + severe.size() + " console warning/error(s) detected:\n  " +
                    severe.stream().map(ConsoleMessage::toString).reduce("", (a, b) -> a + "\n  " + b));
        }
        return this;
    }

    /**
     * Asserts that at least one console message contains the expected text.
     *
     * @throws AssertionError if no message contains the expected text
     */
    public ConsoleMonitor assertContains(String expectedText) {
        List<ConsoleMessage> matches = containing(expectedText);
        if (matches.isEmpty()) {
            throw new AssertionError(
                    "ConsoleMonitor: no console message containing '" + expectedText + "' found. " +
                    "Captured: " + messages.stream().map(ConsoleMessage::text).toList());
        }
        return this;
    }

    /**
     * Asserts that no console message contains the given text (useful for
     * verifying that error conditions were not triggered).
     */
    public ConsoleMonitor assertNotContains(String unexpectedText) {
        List<ConsoleMessage> matches = containing(unexpectedText);
        if (!matches.isEmpty()) {
            throw new AssertionError(
                    "ConsoleMonitor: unexpected console message containing '" + unexpectedText + "' found:\n  " +
                    matches.stream().map(ConsoleMessage::toString).reduce("", (a, b) -> a + "\n  " + b));
        }
        return this;
    }

    /** Returns a compact summary for logging or Allure attachment. */
    public String summary() {
        long errors   = messages.stream().filter(ConsoleMessage::isError).count();
        long warnings = messages.stream().filter(ConsoleMessage::isWarning).count();
        return String.format("ConsoleMonitor{total=%d, errors=%d, warnings=%d}",
                messages.size(), errors, warnings);
    }
}
