package autoqa.recorder;

import autoqa.model.ElementInfo;
import autoqa.model.RecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Redacts sensitive input values in {@link RecordedEvent} objects before
 * they are persisted in recording files.
 *
 * <p>Redaction rules are applied in the following priority order:
 * <ol>
 *   <li>Element {@code type} attribute matches a configured type (e.g., "password")
 *       — always active regardless of configuration.</li>
 *   <li>Element CSS selector contains one of the configured selector substrings.</li>
 *   <li>Element {@code id} or {@code name} matches a built-in pattern covering
 *       common sensitive field naming conventions (pass, secret, token, credit,
 *       card, cvv, ssn, pin).</li>
 * </ol>
 *
 * <p>Only {@link autoqa.model.RecordedEvent.EventType#INPUT} events are examined;
 * all other event types are returned unchanged.
 *
 * <p>Thread-safe: all state is immutable after construction.
 */
public class FieldRedactor {

    /** Placeholder written into the keys field of redacted events. */
    public static final String REDACTED = "[REDACTED]";

    private static final Logger log = LoggerFactory.getLogger(FieldRedactor.class);

    /**
     * Pre-compiled pattern matching common sensitive field id/name values.
     * Case-insensitive; checked against the concatenation of id + name.
     */
    private static final Pattern SENSITIVE_NAME_PATTERN = Pattern.compile(
            "pass|secret|token|credit|card|cvv|ssn|pin",
            Pattern.CASE_INSENSITIVE);

    /** Element type attributes that always trigger redaction (e.g., "password"). */
    private final Set<String> redactTypes;

    /**
     * CSS selector substrings — if the element's generated CSS selector
     * contains any of these strings, the event is redacted.
     */
    private final Set<String> redactSelectors;

    /**
     * Constructs a new {@code FieldRedactor}.
     *
     * @param redactTypes      set of HTML input type values to redact
     *                         (compared case-insensitively); must not be null
     * @param redactSelectors  set of CSS selector substrings that trigger
     *                         redaction; must not be null
     */
    public FieldRedactor(Set<String> redactTypes, Set<String> redactSelectors) {
        this.redactTypes     = redactTypes;
        this.redactSelectors = redactSelectors;
    }

    /**
     * Redacts the event in-place if it matches any configured redaction rule.
     *
     * <p>The {@code keys} field of the event's {@link autoqa.model.InputData}
     * is replaced with {@link #REDACTED} when a rule fires.
     *
     * @param event the event to inspect and potentially redact
     * @return {@code true} if the event was redacted, {@code false} otherwise
     */
    public boolean redact(RecordedEvent event) {
        if (event.getEventType() != RecordedEvent.EventType.INPUT) return false;
        if (event.getInputData() == null)               return false;
        if (event.getInputData().getKeys() == null)     return false;
        // Already redacted — idempotent
        if (REDACTED.equals(event.getInputData().getKeys())) return true;

        if (shouldRedact(event)) {
            event.getInputData().setKeys(REDACTED);
            log.debug("Redacted sensitive input event");
            return true;
        }
        return false;
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private boolean shouldRedact(RecordedEvent event) {
        ElementInfo el = event.getElement();

        // Rule 1: element type attribute (e.g., type="password")
        if (el != null && el.getType() != null) {
            if (redactTypes.contains(el.getType().toLowerCase())) {
                return true;
            }
        }

        // Rule 2: CSS selector substring match
        if (el != null && el.getCss() != null) {
            for (String selector : redactSelectors) {
                if (el.getCss().contains(selector.trim())) {
                    return true;
                }
            }
        }

        // Rule 3: element id / name matches sensitive naming pattern
        if (el != null) {
            String idPart   = el.getId()   != null ? el.getId()   : "";
            String namePart = el.getName() != null ? el.getName() : "";
            String combined = idPart + namePart;
            if (!combined.isBlank()
                    && SENSITIVE_NAME_PATTERN.matcher(combined).find()) {
                return true;
            }
        }

        return false;
    }
}
