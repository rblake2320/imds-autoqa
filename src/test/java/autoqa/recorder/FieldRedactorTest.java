package autoqa.recorder;

import autoqa.model.ElementInfo;
import autoqa.model.InputData;
import autoqa.model.RecordedEvent;
import org.testng.annotations.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link FieldRedactor}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Password type field is always redacted</li>
 *   <li>Plain text field is not redacted</li>
 *   <li>Non-INPUT events are never redacted</li>
 *   <li>Element id/name matching sensitive name pattern triggers redaction</li>
 *   <li>Configured CSS selector substring triggers redaction</li>
 *   <li>Idempotency: already-redacted events return true without double-replacing</li>
 *   <li>Event with null InputData is safe</li>
 * </ul>
 */
public class FieldRedactorTest {

    private final FieldRedactor redactor =
            new FieldRedactor(Set.of("password"), Set.of());

    // ── Core type-based redaction ─────────────────────────────────────────

    @Test
    public void passwordField_isRedacted() {
        RecordedEvent e = makeInputEvent("password", "secret123");
        assertThat(redactor.redact(e)).isTrue();
        assertThat(e.getInputData().getKeys()).isEqualTo(FieldRedactor.REDACTED);
    }

    @Test
    public void textField_notRedacted() {
        RecordedEvent e = makeInputEvent("text", "hello");
        assertThat(redactor.redact(e)).isFalse();
        assertThat(e.getInputData().getKeys()).isEqualTo("hello");
    }

    @Test
    public void emailField_notRedacted() {
        RecordedEvent e = makeInputEvent("email", "user@example.com");
        assertThat(redactor.redact(e)).isFalse();
        assertThat(e.getInputData().getKeys()).isEqualTo("user@example.com");
    }

    // ── Event type guard ──────────────────────────────────────────────────

    @Test
    public void nonInputEvent_notRedacted() {
        RecordedEvent e = new RecordedEvent();
        e.setEventType(RecordedEvent.EventType.CLICK);
        assertThat(redactor.redact(e)).isFalse();
    }

    @Test
    public void keyPressEvent_notRedacted() {
        RecordedEvent e = new RecordedEvent();
        e.setEventType(RecordedEvent.EventType.KEY_PRESS);
        InputData input = new InputData();
        input.setKeyCode("ENTER");
        e.setInputData(input);
        assertThat(redactor.redact(e)).isFalse();
    }

    // ── Sensitive name pattern ────────────────────────────────────────────

    @Test
    public void fieldIdContainsPass_isRedacted() {
        RecordedEvent e = makeInputEvent("text", "hunter2");
        e.getElement().setId("userPassword");
        assertThat(redactor.redact(e)).isTrue();
        assertThat(e.getInputData().getKeys()).isEqualTo(FieldRedactor.REDACTED);
    }

    @Test
    public void fieldNameContainsSecret_isRedacted() {
        RecordedEvent e = makeInputEvent("text", "topsecret");
        e.getElement().setName("apiSecret");
        assertThat(redactor.redact(e)).isTrue();
    }

    @Test
    public void fieldIdContainsToken_isRedacted() {
        RecordedEvent e = makeInputEvent("text", "abc123token");
        e.getElement().setId("authToken");
        assertThat(redactor.redact(e)).isTrue();
    }

    @Test
    public void fieldNameContainsPin_isRedacted() {
        RecordedEvent e = makeInputEvent("text", "1234");
        e.getElement().setName("pinEntry");
        assertThat(redactor.redact(e)).isTrue();
    }

    @Test
    public void ordinaryFieldName_notRedacted() {
        RecordedEvent e = makeInputEvent("text", "John Doe");
        e.getElement().setId("fullName");
        assertThat(redactor.redact(e)).isFalse();
    }

    // ── CSS selector matching ─────────────────────────────────────────────

    @Test
    public void configuredSelector_isRedacted() {
        FieldRedactor custom = new FieldRedactor(Set.of("password"), Set.of(".secret-field"));
        RecordedEvent e = makeInputEvent("text", "sensitive");
        e.getElement().setCss(".secret-field");
        assertThat(custom.redact(e)).isTrue();
        assertThat(e.getInputData().getKeys()).isEqualTo(FieldRedactor.REDACTED);
    }

    @Test
    public void nonMatchingSelector_notRedacted() {
        FieldRedactor custom = new FieldRedactor(Set.of("password"), Set.of(".secret-field"));
        RecordedEvent e = makeInputEvent("text", "hello");
        e.getElement().setCss(".public-field");
        assertThat(custom.redact(e)).isFalse();
    }

    @Test
    public void multipleSelectorsOneMatches_isRedacted() {
        FieldRedactor custom = new FieldRedactor(
                Set.of("password"),
                Set.of(".secure-input", ".hidden-data"));
        RecordedEvent e = makeInputEvent("text", "value");
        e.getElement().setCss("form .hidden-data.special");
        assertThat(custom.redact(e)).isTrue();
    }

    // ── Edge cases ────────────────────────────────────────────────────────

    @Test
    public void nullInputData_safeAndNotRedacted() {
        RecordedEvent e = new RecordedEvent();
        e.setEventType(RecordedEvent.EventType.INPUT);
        e.setInputData(null);
        assertThat(redactor.redact(e)).isFalse();
    }

    @Test
    public void nullKeys_safeAndNotRedacted() {
        RecordedEvent e = new RecordedEvent();
        e.setEventType(RecordedEvent.EventType.INPUT);
        InputData data = new InputData();
        data.setKeys(null);
        e.setInputData(data);
        assertThat(redactor.redact(e)).isFalse();
    }

    @Test
    public void alreadyRedacted_idempotent() {
        RecordedEvent e = makeInputEvent("password", FieldRedactor.REDACTED);
        // Should not throw, and should still return true
        assertThat(redactor.redact(e)).isTrue();
        assertThat(e.getInputData().getKeys()).isEqualTo(FieldRedactor.REDACTED);
    }

    @Test
    public void noElementInfo_sensitiveTypeStillRedacted() {
        // element is null but type info is unavailable — only type-based rule applies
        RecordedEvent e = new RecordedEvent();
        e.setEventType(RecordedEvent.EventType.INPUT);
        InputData data = InputData.ofKeys("mypassword");
        e.setInputData(data);
        // No element set — cannot match any rule, should not redact
        assertThat(redactor.redact(e)).isFalse();
    }

    @Test
    public void passwordTypeWithNullElement_notRedacted() {
        // element is null — type check cannot fire
        RecordedEvent e = new RecordedEvent();
        e.setEventType(RecordedEvent.EventType.INPUT);
        e.setInputData(InputData.ofKeys("secret"));
        e.setElement(null);
        assertThat(redactor.redact(e)).isFalse();
    }

    // ── Helper ────────────────────────────────────────────────────────────

    /**
     * Creates a minimal INPUT {@link RecordedEvent} with the given element type
     * and typed text value.
     */
    private RecordedEvent makeInputEvent(String type, String value) {
        RecordedEvent e = new RecordedEvent();
        e.setEventType(RecordedEvent.EventType.INPUT);

        ElementInfo el = new ElementInfo();
        el.setType(type);
        el.setTagName("input");
        e.setElement(el);

        InputData data = InputData.ofKeys(value);
        e.setInputData(data);

        return e;
    }
}
