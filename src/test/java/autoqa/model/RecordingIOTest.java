package autoqa.model;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Round-trip serialization tests for RecordingIO.
 * Verifies that write(read(json)) produces zero data loss.
 */
public class RecordingIOTest {

    // ── Round-trip ────────────────────────────────────────────────────────

    @Test
    public void roundTrip_minimalSession() throws IOException {
        RecordedSession original = buildMinimalSession();
        Path tmp = Files.createTempFile("autoqa-test-", ".json");
        try {
            RecordingIO.write(original, tmp);
            RecordedSession loaded = RecordingIO.read(tmp);
            assertThat(loaded.getSessionId()).isEqualTo(original.getSessionId());
            assertThat(loaded.getSchemaVersion()).isEqualTo(RecordedSession.CURRENT_SCHEMA_VERSION);
            assertThat(loaded.getEventCount()).isEqualTo(original.getEventCount());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void roundTrip_fullSession() throws IOException {
        RecordedSession original = buildFullSession();
        Path tmp = Files.createTempFile("autoqa-test-full-", ".json");
        try {
            RecordingIO.write(original, tmp);
            RecordedSession loaded = RecordingIO.read(tmp);

            assertThat(loaded.getSessionId()).isEqualTo(original.getSessionId());
            assertThat(loaded.getEventCount()).isEqualTo(original.getEventCount());
            assertThat(loaded.getBrowserName()).isEqualTo("Microsoft Edge");
            assertThat(loaded.getRecordedBy()).isEqualTo("test-user");

            RecordedEvent click = loaded.getEvents().get(0);
            assertThat(click.getEventType()).isEqualTo(RecordedEvent.EventType.CLICK);
            assertThat(click.getElement().getId()).isEqualTo("submit-btn");
            assertThat(click.getElement().getCss()).isEqualTo("#submit-btn");
            assertThat(click.getCoordinates().getX()).isEqualTo(100.0);

            RecordedEvent input = loaded.getEvents().get(1);
            assertThat(input.getEventType()).isEqualTo(RecordedEvent.EventType.INPUT);
            assertThat(input.getInputData().getKeys()).isEqualTo("hello world");

            RecordedEvent select = loaded.getEvents().get(2);
            assertThat(select.getInputData().getSelectedOption().getText()).isEqualTo("Option A");
            assertThat(select.getInputData().getSelectedOption().getIndex()).isEqualTo(1);

            RecordedEvent alert = loaded.getEvents().get(3);
            assertThat(alert.getInputData().getAlertAction())
                    .isEqualTo(InputData.AlertAction.ACCEPT);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    public void toJson_fromJson_roundTrip() throws IOException {
        RecordedSession original = buildFullSession();
        String json = RecordingIO.toJson(original);
        assertThat(json).contains("schemaVersion");
        assertThat(json).contains(original.getSessionId());

        RecordedSession loaded = RecordingIO.fromJson(json);
        assertThat(loaded.getSessionId()).isEqualTo(original.getSessionId());
        assertThat(loaded.getEventCount()).isEqualTo(original.getEventCount());
    }

    @Test
    public void nullFields_notWrittenToJson() throws IOException {
        RecordedSession session = buildMinimalSession();
        String json = RecordingIO.toJson(session);
        // Non-null-include means absent fields are omitted
        assertThat(json).doesNotContain("\"browserVersion\" : null");
        assertThat(json).doesNotContain("\"endTimestamp\" : null");
    }

    @Test
    public void frameChain_survivesRoundTrip() throws IOException {
        RecordedSession session = buildMinimalSession();
        RecordedEvent frameEvent = new RecordedEvent();
        frameEvent.setEventType(RecordedEvent.EventType.CLICK);
        frameEvent.setTimestamp(Instant.now());
        frameEvent.setFrameChain(List.of("frame#outer", "iframe[name='inner']"));
        session.addEvent(frameEvent);

        String json = RecordingIO.toJson(session);
        RecordedSession loaded = RecordingIO.fromJson(json);
        assertThat(loaded.getEvents().get(0).getFrameChain())
                .containsExactly("frame#outer", "iframe[name='inner']");
    }

    // ── Error cases ───────────────────────────────────────────────────────

    @Test(expectedExceptions = IOException.class)
    public void read_missingFile_throwsIOException() throws IOException {
        RecordingIO.read(Path.of("nonexistent-file-xyz.json"));
    }

    @Test
    public void isVersionSupported_returnsTrue_forCurrentVersion() {
        RecordedSession s = new RecordedSession();
        assertThat(s.isVersionSupported()).isTrue();
    }

    @Test
    public void isVersionSupported_returnsFalse_forOldVersion() {
        RecordedSession s = new RecordedSession();
        s.setSchemaVersion("0.9");
        assertThat(s.isVersionSupported()).isFalse();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private RecordedSession buildMinimalSession() {
        RecordedSession s = new RecordedSession();
        s.setSessionId(UUID.randomUUID().toString());
        s.setStartTimestamp(Instant.now());
        return s;
    }

    private RecordedSession buildFullSession() {
        RecordedSession s = buildMinimalSession();
        s.setEndTimestamp(Instant.now());
        s.setRecordedBy("test-user");
        s.setBrowserVersion("122.0.0.0");
        s.setOsName("Windows 11");

        // Event 0: CLICK
        RecordedEvent click = new RecordedEvent();
        click.setEventType(RecordedEvent.EventType.CLICK);
        click.setTimestamp(Instant.now());
        click.setUrl("https://example.com/form");
        click.setPageTitle("Test Form");
        ElementInfo el = new ElementInfo();
        el.setTagName("button");
        el.setId("submit-btn");
        el.setCss("#submit-btn");
        el.setXpath("//button[@id='submit-btn']");
        el.setBoundingBox(new BoundingBox(90.0, 200.0, 80.0, 36.0));
        el.setAttributes(Map.of("class", "btn btn-primary", "type", "submit"));
        click.setElement(el);
        click.setCoordinates(new Coordinates(100.0, 218.0));
        s.addEvent(click);

        // Event 1: INPUT
        RecordedEvent input = new RecordedEvent();
        input.setEventType(RecordedEvent.EventType.INPUT);
        input.setTimestamp(Instant.now());
        ElementInfo inputEl = new ElementInfo();
        inputEl.setTagName("input");
        inputEl.setId("username");
        inputEl.setType("text");
        input.setElement(inputEl);
        input.setInputData(InputData.ofKeys("hello world"));
        s.addEvent(input);

        // Event 2: SELECT
        RecordedEvent select = new RecordedEvent();
        select.setEventType(RecordedEvent.EventType.SELECT);
        select.setTimestamp(Instant.now());
        InputData selectData = InputData.ofSelect("Option A");
        selectData.getSelectedOption().setValue("opt-a");
        selectData.getSelectedOption().setIndex(1);
        select.setInputData(selectData);
        s.addEvent(select);

        // Event 3: ALERT accept
        RecordedEvent alert = new RecordedEvent();
        alert.setEventType(RecordedEvent.EventType.ALERT);
        alert.setTimestamp(Instant.now());
        InputData alertData = new InputData();
        alertData.setAlertAction(InputData.AlertAction.ACCEPT);
        alert.setInputData(alertData);
        s.addEvent(alert);

        return s;
    }
}
