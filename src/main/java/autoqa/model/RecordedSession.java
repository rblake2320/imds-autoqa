package autoqa.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Top-level container for a complete recording session.
 * Maps 1:1 to the root object defined in {@code event-schema.json}.
 *
 * <p>Created by {@code RecordingSession} during recording and consumed by
 * {@code PlayerEngine} during playback.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordedSession {

    /** Current schema version — must match event-schema.json. */
    public static final String CURRENT_SCHEMA_VERSION = "1.0";

    @JsonProperty("schemaVersion")
    private String schemaVersion = CURRENT_SCHEMA_VERSION;

    @JsonProperty("sessionId")
    private String sessionId;

    @JsonProperty("startTimestamp")
    private Instant startTimestamp;

    @JsonProperty("endTimestamp")
    private Instant endTimestamp;

    @JsonProperty("browserName")
    private String browserName = "Microsoft Edge";

    @JsonProperty("browserVersion")
    private String browserVersion;

    @JsonProperty("osName")
    private String osName;

    @JsonProperty("recordedBy")
    private String recordedBy;

    /**
     * When true the {@code events} field contains AES-encrypted JSON
     * rather than plain event objects. RecordingIO handles de/encryption.
     */
    @JsonProperty("encrypted")
    private boolean encrypted = false;

    @JsonProperty("events")
    private List<RecordedEvent> events = new CopyOnWriteArrayList<>();

    public RecordedSession() {}

    // ── Getters ──────────────────────────────────────────────────────────

    public String              getSchemaVersion()  { return schemaVersion; }
    public String              getSessionId()      { return sessionId; }
    public Instant             getStartTimestamp() { return startTimestamp; }
    public Instant             getEndTimestamp()   { return endTimestamp; }
    public String              getBrowserName()    { return browserName; }
    public String              getBrowserVersion() { return browserVersion; }
    public String              getOsName()         { return osName; }
    public String              getRecordedBy()     { return recordedBy; }
    public boolean             isEncrypted()       { return encrypted; }
    public List<RecordedEvent> getEvents()         { return events; }

    // ── Setters ──────────────────────────────────────────────────────────

    public void setSchemaVersion(String v)          { this.schemaVersion = v; }
    public void setSessionId(String id)             { this.sessionId = id; }
    public void setStartTimestamp(Instant t)        { this.startTimestamp = t; }
    public void setEndTimestamp(Instant t)          { this.endTimestamp = t; }
    public void setBrowserName(String name)         { this.browserName = name; }
    public void setBrowserVersion(String ver)       { this.browserVersion = ver; }
    public void setOsName(String os)                { this.osName = os; }
    public void setRecordedBy(String user)          { this.recordedBy = user; }
    public void setEncrypted(boolean encrypted)     { this.encrypted = encrypted; }
    public void setEvents(List<RecordedEvent> evts) {
        this.events = evts != null ? new CopyOnWriteArrayList<>(evts) : new CopyOnWriteArrayList<>();
    }

    // ── Convenience ──────────────────────────────────────────────────────

    public void addEvent(RecordedEvent event) {
        if (events == null) events = new CopyOnWriteArrayList<>();
        events.add(event);
    }

    public int getEventCount() {
        return events == null ? 0 : events.size();
    }

    public boolean isVersionSupported() {
        return CURRENT_SCHEMA_VERSION.equals(schemaVersion);
    }

    @Override
    public String toString() {
        return String.format("RecordedSession{id='%s', events=%d, encrypted=%b}",
                sessionId, getEventCount(), encrypted);
    }
}
