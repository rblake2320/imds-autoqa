package autoqa.model;

import org.testng.annotations.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for RecordedEvent field validation and convenience methods.
 */
public class RecordedEventTest {

    @Test
    public void hasElement_trueWhenElementPresent() {
        RecordedEvent e = new RecordedEvent();
        assertThat(e.hasElement()).isFalse();
        e.setElement(new ElementInfo());
        assertThat(e.hasElement()).isTrue();
    }

    @Test
    public void hasInputData_trueWhenPresent() {
        RecordedEvent e = new RecordedEvent();
        assertThat(e.hasInputData()).isFalse();
        e.setInputData(new InputData());
        assertThat(e.hasInputData()).isTrue();
    }

    @Test
    public void isInFrame_falseWhenChainEmpty() {
        RecordedEvent e = new RecordedEvent();
        assertThat(e.isInFrame()).isFalse();
        e.setFrameChain(List.of());
        assertThat(e.isInFrame()).isFalse();
    }

    @Test
    public void isInFrame_trueWhenChainNonEmpty() {
        RecordedEvent e = new RecordedEvent();
        e.setFrameChain(List.of("iframe#main"));
        assertThat(e.isInFrame()).isTrue();
    }

    @Test
    public void allEventTypesDeserialized() throws Exception {
        for (RecordedEvent.EventType type : RecordedEvent.EventType.values()) {
            String json = """
                    {"schemaVersion":"1.0","sessionId":"s1","startTimestamp":"2024-01-01T00:00:00Z",
                     "events":[{"eventType":"%s","timestamp":"2024-01-01T00:00:01Z"}]}
                    """.formatted(type.name());
            RecordedSession session = RecordingIO.fromJson(json);
            assertThat(session.getEvents().get(0).getEventType()).isEqualTo(type);
        }
    }

    @Test
    public void elementInfo_hasAnyLocator_requiresAtLeastOneField() {
        ElementInfo e = new ElementInfo();
        assertThat(e.hasAnyLocator()).isFalse();
        e.setId("my-id");
        assertThat(e.hasAnyLocator()).isTrue();
    }

    @Test
    public void elementLocator_healedFlag_setForHealedStrategy() {
        ElementLocator loc = new ElementLocator(ElementLocator.Strategy.HEALED, "//div");
        assertThat(loc.isHealed()).isTrue();

        ElementLocator normal = new ElementLocator(ElementLocator.Strategy.CSS, "#btn");
        assertThat(normal.isHealed()).isFalse();
    }

    @Test
    public void inputData_factories_populateCorrectFields() {
        InputData keys = InputData.ofKeys("hello");
        assertThat(keys.getKeys()).isEqualTo("hello");
        assertThat(keys.getKeyCode()).isNull();

        InputData key = InputData.ofKey("ENTER");
        assertThat(key.getKeyCode()).isEqualTo("ENTER");
        assertThat(key.getKeys()).isNull();

        InputData select = InputData.ofSelect("Option B");
        assertThat(select.getSelectedOption().getText()).isEqualTo("Option B");
    }

    @Test
    public void uiElement_isConfidentEnough_threshold() {
        UIElement e = new UIElement("button", "Submit", 0.92, null);
        assertThat(e.isConfidentEnough(0.75)).isTrue();
        assertThat(e.isConfidentEnough(0.95)).isFalse();
    }

    @Test
    public void boundingBox_coordinates_fieldValues() {
        BoundingBox bb = new BoundingBox(10.0, 20.0, 100.0, 40.0);
        assertThat(bb.getX()).isEqualTo(10.0);
        assertThat(bb.getY()).isEqualTo(20.0);
        assertThat(bb.getWidth()).isEqualTo(100.0);
        assertThat(bb.getHeight()).isEqualTo(40.0);

        Coordinates c = new Coordinates(55.5, 120.0);
        assertThat(c.getX()).isEqualTo(55.5);
        assertThat(c.getY()).isEqualTo(120.0);
    }

    @Test
    public void recordedSession_addEvent_incrementsCount() {
        RecordedSession s = new RecordedSession();
        assertThat(s.getEventCount()).isZero();
        RecordedEvent e = new RecordedEvent();
        e.setEventType(RecordedEvent.EventType.NAVIGATE);
        e.setTimestamp(Instant.now());
        s.addEvent(e);
        assertThat(s.getEventCount()).isEqualTo(1);
    }
}
