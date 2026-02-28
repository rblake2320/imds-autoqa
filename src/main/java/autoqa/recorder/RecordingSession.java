package autoqa.recorder;

import autoqa.model.RecordedEvent;
import autoqa.model.RecordedSession;
import autoqa.model.RecordingIO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single recording session that wires together {@link CDPConnector},
 * {@link DOMEnricher}, {@link FieldRedactor}, and an {@link InputCaptureAdapter}
 * to capture, enrich, and persist user interactions with Microsoft Edge.
 *
 * <h3>Typical usage</h3>
 * <pre>{@code
 * RecordingSession session = new RecordingSession(config);
 * session.start();
 * // user interacts with the browser ...
 * Path saved = session.stop();
 * System.out.println("Recording saved to: " + saved);
 * }</pre>
 *
 * <p>Events are delivered from the {@link InputCaptureAdapter} on a
 * background thread. DOM enrichment and redaction happen synchronously
 * inside {@link #onOsEvent(RecordedEvent)} on that same thread, so no
 * additional synchronisation is needed beyond the {@link AtomicBoolean}
 * guard on {@code running}.
 */
public class RecordingSession {

    private static final Logger log = LoggerFactory.getLogger(RecordingSession.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** JS expression used to read the current page URL. */
    private static final String JS_HREF = "window.location.href";

    /** JS expression used to read the current page title. */
    private static final String JS_TITLE = "document.title";

    private final RecorderConfig config;
    private final CDPConnector cdp;
    private final DOMEnricher domEnricher;
    private final FieldRedactor redactor;
    private final InputCaptureAdapter inputCapture;

    private final RecordedSession data;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ── Construction ──────────────────────────────────────────────────────

    /**
     * Creates a recording session using the supplied config and a live
     * {@link OSInputCapture} instance for OS-level event capture.
     *
     * <p>CDP and DOM enrichment objects are also initialised here; they are
     * not yet connected to the browser until {@link #start()} is called.
     *
     * @param config recorder configuration
     */
    public RecordingSession(RecorderConfig config) {
        this(config, new OSInputCapture(
                config.getUrlWhitelist().isEmpty()
                        ? java.util.Collections.emptySet()
                        : new java.util.HashSet<>(config.getUrlWhitelist())));
    }

    /**
     * Package-private constructor that accepts a custom {@link InputCaptureAdapter},
     * enabling unit tests and integration tests to inject a mock or stub.
     *
     * @param config       recorder configuration
     * @param inputCapture OS-level input hook implementation
     */
    RecordingSession(RecorderConfig config, InputCaptureAdapter inputCapture) {
        this.config       = config;
        this.inputCapture = inputCapture;
        this.cdp          = new CDPConnector(config.getCdpPort(), config.getCdpWsTimeoutSec());
        this.domEnricher  = new DOMEnricher(cdp);
        this.redactor     = new FieldRedactor(config.getRedactTypes(), config.getRedactSelectors());

        // Populate session metadata
        this.data = new RecordedSession();
        data.setSessionId(UUID.randomUUID().toString());
        data.setOsName(System.getProperty("os.name", "unknown"));
        data.setRecordedBy(System.getProperty("user.name", "unknown"));
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Starts the recording session.
     *
     * <ol>
     *   <li>Connects to Edge via CDP.</li>
     *   <li>Enables the {@code Page} domain to receive navigation events.</li>
     *   <li>Registers a CDP event listener for {@code Page.loadEventFired}
     *       so URL changes triggered by full page loads are captured.</li>
     *   <li>Starts the {@link InputCaptureAdapter} to hook OS mouse/keyboard.</li>
     * </ol>
     *
     * @throws IOException if the CDP connection cannot be established
     * @throws IllegalStateException if the session is already running
     */
    public void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("RecordingSession is already running");
        }

        log.info("Starting recording session {} on CDP port {}",
                data.getSessionId(), config.getCdpPort());

        // Connect to Edge CDP
        cdp.connect();

        // Enable Page domain — required for Page.loadEventFired events
        cdp.enable("Page");

        // Register CDP event listener for page navigation events
        cdp.addEventListeners(this::onCdpEvent);

        // Mark session start time
        data.setStartTimestamp(Instant.now());

        // Read Edge version from the browser
        captureBrowserVersion();

        // Start OS-level input capture
        inputCapture.start(this::onOsEvent);

        log.info("Recording session {} started — capturing events", data.getSessionId());
    }

    /**
     * Stops the recording session, serialises all captured events to a JSON
     * file, and returns the path to that file.
     *
     * <p>It is safe to call {@code stop()} more than once; subsequent calls
     * return immediately with {@code null}.
     *
     * @return absolute path to the written recording file
     * @throws IOException if the output file cannot be written
     */
    public Path stop() throws IOException {
        if (!running.compareAndSet(true, false)) {
            log.warn("RecordingSession.stop() called on a session that is not running");
            return null;
        }

        log.info("Stopping recording session {} ({} events captured)",
                data.getSessionId(), data.getEventCount());

        // Stop OS hooks first so no new events arrive while we're writing
        inputCapture.stop();

        // Record end time
        data.setEndTimestamp(Instant.now());

        // Close CDP connection
        cdp.close();

        // Build output path and write JSON
        Path outputPath = buildOutputPath();
        Files.createDirectories(outputPath.getParent());
        RecordingIO.write(data, outputPath);

        log.info("Recording session {} saved to {}", data.getSessionId(), outputPath);
        return outputPath;
    }

    /**
     * Returns the number of events captured so far.
     * Safe to call while the session is running.
     */
    public int getEventCount() {
        return data.getEventCount();
    }

    /**
     * Returns the session identifier (UUID assigned at construction time).
     */
    public String getSessionId() {
        return data.getSessionId();
    }

    // ── Package-visible callback: OS input events ─────────────────────────

    /**
     * Receives a raw {@link RecordedEvent} from the {@link InputCaptureAdapter},
     * enriches it with DOM metadata and the current URL, applies redaction,
     * and appends it to the session.
     *
     * <p>Called on the input-capture thread. Guard is lightweight ({@link AtomicBoolean});
     * no blocking is performed on the happy path.
     *
     * @param event raw event from OS hooks
     */
    void onOsEvent(RecordedEvent event) {
        if (!running.get()) return;

        // DOM enrichment — only for mouse events that have coordinates
        if (event.getCoordinates() != null) {
            double x = event.getCoordinates().getX();
            double y = event.getCoordinates().getY();

            try {
                autoqa.model.ElementInfo elementInfo = domEnricher.enrich(x, y);
                event.setElement(elementInfo);

                List<String> frameChain = domEnricher.detectFrameChain(x, y);
                if (!frameChain.isEmpty()) {
                    event.setFrameChain(frameChain);
                }
            } catch (Exception e) {
                // DOM enrichment failure must not drop the event — log and continue
                log.debug("DOM enrichment failed for event at ({}, {}): {}", x, y, e.getMessage());
            }
        }

        // Capture current URL and page title via CDP
        try {
            String href  = evaluateStringExpression(JS_HREF);
            String title = evaluateStringExpression(JS_TITLE);
            event.setUrl(href);
            event.setPageTitle(title);
        } catch (Exception e) {
            log.debug("Failed to read current URL/title: {}", e.getMessage());
        }

        // Redact sensitive input
        redactor.redact(event);

        // Append to session
        data.addEvent(event);
        log.debug("Event recorded: type={}, url='{}'", event.getEventType(), event.getUrl());
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /**
     * Handles CDP events pushed by the browser. Currently listens for
     * {@code Page.loadEventFired} to log URL transitions.
     */
    private void onCdpEvent(JsonNode eventNode) {
        if (!running.get()) return;

        JsonNode methodNode = eventNode.get("method");
        if (methodNode == null) return;

        String method = methodNode.asText();
        if ("Page.loadEventFired".equals(method)) {
            try {
                String newUrl = evaluateStringExpression(JS_HREF);
                log.debug("Page load event fired — current URL: {}", newUrl);
            } catch (Exception e) {
                log.debug("Could not read URL after Page.loadEventFired: {}", e.getMessage());
            }
        }
    }

    /**
     * Uses {@code Runtime.evaluate} to read a string value from the browser.
     *
     * @param expression JavaScript expression that evaluates to a string
     * @return the string result, or {@code null} if the result is absent/null
     */
    private String evaluateStringExpression(String expression) throws IOException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("expression", expression);
        params.put("returnByValue", true);

        JsonNode result = cdp.sendCommand("Runtime.evaluate", params);
        if (result == null || result.isNull()) return null;

        JsonNode value = result.get("value");
        return (value == null || value.isNull()) ? null : value.asText();
    }

    /**
     * Attempts to read the Edge browser version from the CDP target info.
     * Failure is non-fatal.
     */
    private void captureBrowserVersion() {
        try {
            JsonNode result = cdp.sendCommand("Browser.getVersion", MAPPER.createObjectNode());
            if (result != null) {
                JsonNode productNode = result.get("product");
                if (productNode != null && !productNode.isNull()) {
                    data.setBrowserVersion(productNode.asText());
                    log.debug("Browser version: {}", productNode.asText());
                }
            }
        } catch (Exception e) {
            log.debug("Could not read browser version: {}", e.getMessage());
        }
    }

    /**
     * Builds the output file path: {@code {outputDir}/{prefix}-{epochMillis}.json}.
     */
    private Path buildOutputPath() {
        String filename = config.getSessionPrefix() + "-" + System.currentTimeMillis() + ".json";
        return Paths.get(config.getOutputDir()).resolve(filename);
    }
}
