package autoqa.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * A single user interaction captured during a recording session.
 * Maps 1:1 to the {@code RecordedEvent} definition in {@code event-schema.json}.
 *
 * <p>Event types are defined in {@link EventType}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordedEvent {

    public enum EventType {
        CLICK, DOUBLE_CLICK, CONTEXT_MENU, INPUT, KEY_PRESS,
        SELECT, SCROLL, NAVIGATE, ALERT, WINDOW_SWITCH,
        FRAME_SWITCH, HOVER, DRAG_DROP, WAIT,
        /** Verification checkpoint — UFT checkpoint equivalent. */
        CHECKPOINT
    }

    @JsonProperty("eventType")
    private EventType eventType;

    @JsonProperty("timestamp")
    private Instant timestamp;

    /** Full URL of the page when the event occurred. */
    @JsonProperty("url")
    private String url;

    @JsonProperty("pageTitle")
    private String pageTitle;

    /** Element targeted by this event. Null for NAVIGATE and ALERT. */
    @JsonProperty("element")
    private ElementInfo element;

    /** Keyboard / dropdown / alert data. Null for click/scroll/navigate. */
    @JsonProperty("inputData")
    private InputData inputData;

    /** Raw screen coordinates at the moment of the event. */
    @JsonProperty("coordinates")
    private Coordinates coordinates;

    /**
     * Ordered list of frame locators needed to reach the target element.
     * Empty list means the element is in the top-level document.
     */
    @JsonProperty("frameChain")
    private List<String> frameChain;

    /** Browser window handle at the time of the event. */
    @JsonProperty("windowHandle")
    private String windowHandle;

    /** Optional base64-encoded PNG screenshot at the moment of the event. */
    @JsonProperty("screenshotBase64")
    private String screenshotBase64;

    /** Human-readable description for generated test step names. */
    @JsonProperty("comment")
    private String comment;

    /**
     * Verification checkpoint data.  Present only when {@code eventType == CHECKPOINT}.
     * Equivalent to UFT One's text/image/accessibility checkpoint.
     */
    @JsonProperty("checkpointData")
    private CheckpointData checkpointData;

    /**
     * Logical name referencing a {@link ObjectRepository} entry.
     * When set, the player resolves this name from the shared OR instead of
     * using the inline {@code element} locators.
     */
    @JsonProperty("objectName")
    private String objectName;

    public RecordedEvent() {}

    // ── Getters ──────────────────────────────────────────────────────────

    public EventType    getEventType()        { return eventType; }
    public Instant      getTimestamp()        { return timestamp; }
    public String       getUrl()              { return url; }
    public String       getPageTitle()        { return pageTitle; }
    public ElementInfo  getElement()          { return element; }
    public InputData    getInputData()        { return inputData; }
    public Coordinates  getCoordinates()      { return coordinates; }
    public List<String> getFrameChain()       { return frameChain; }
    public String       getWindowHandle()     { return windowHandle; }
    public String       getScreenshotBase64() { return screenshotBase64; }
    public String          getComment()          { return comment; }
    public CheckpointData  getCheckpointData()   { return checkpointData; }
    public String          getObjectName()        { return objectName; }

    // ── Setters ──────────────────────────────────────────────────────────

    public void setEventType(EventType eventType)          { this.eventType = eventType; }
    public void setTimestamp(Instant timestamp)            { this.timestamp = timestamp; }
    public void setUrl(String url)                         { this.url = url; }
    public void setPageTitle(String pageTitle)             { this.pageTitle = pageTitle; }
    public void setElement(ElementInfo element)            { this.element = element; }
    public void setInputData(InputData inputData)          { this.inputData = inputData; }
    public void setCoordinates(Coordinates coordinates)    { this.coordinates = coordinates; }
    public void setFrameChain(List<String> frameChain)     { this.frameChain = frameChain; }
    public void setWindowHandle(String windowHandle)       { this.windowHandle = windowHandle; }
    public void setScreenshotBase64(String s)                    { this.screenshotBase64 = s; }
    public void setComment(String comment)                       { this.comment = comment; }
    public void setCheckpointData(CheckpointData checkpointData) { this.checkpointData = checkpointData; }
    public void setObjectName(String objectName)                 { this.objectName = objectName; }

    // ── Convenience ──────────────────────────────────────────────────────

    @JsonIgnore public boolean hasElement()       { return element != null; }
    @JsonIgnore public boolean hasObjectName()    { return objectName != null && !objectName.isBlank(); }
    @JsonIgnore public boolean isCheckpoint()     { return eventType == EventType.CHECKPOINT; }
    @JsonIgnore public boolean hasInputData() { return inputData != null; }
    @JsonIgnore public boolean isInFrame()    { return frameChain != null && !frameChain.isEmpty(); }

    @Override
    public String toString() {
        return String.format("RecordedEvent{type=%s, url='%s', element=%s}",
                eventType, url, element);
    }
}
