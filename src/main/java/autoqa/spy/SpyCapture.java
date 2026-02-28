package autoqa.spy;

import java.time.Instant;
import java.util.Map;

/**
 * An immutable capture record from the {@link ApplicationSpy}.
 *
 * <p>Each capture represents one observable event in the application under test:
 * a network request, a storage change, a DOM mutation, a console message, or a custom event.
 */
public final class SpyCapture {

    /** The type of captured event. */
    public enum Type {
        NETWORK_REQUEST,    // XHR/Fetch request sent
        NETWORK_RESPONSE,   // XHR/Fetch response received (with body)
        STORAGE_SET,        // localStorage/sessionStorage key set
        STORAGE_REMOVE,     // localStorage/sessionStorage key removed
        COOKIE_CHANGE,      // document.cookie changed
        DOM_MUTATION,       // DOM node added/removed/modified
        CONSOLE_LOG,        // console.log/warn/error message
        JS_VARIABLE,        // monitored JS global variable changed
        CUSTOM_EVENT        // custom application event
    }

    private final Type                type;
    private final String              source;      // URL, key name, selector, etc.
    private final String              data;        // request/response body, new value, etc.
    private final String              extra;       // method, old value, element tag, etc.
    private final Map<String, String> headers;
    private final int                 statusCode;
    private final long                durationMs;
    private final Instant             timestamp;
    private final long                sequenceNo;  // monotonically increasing capture index

    // Full constructor
    public SpyCapture(Type type, String source, String data, String extra,
                      Map<String, String> headers, int statusCode, long durationMs,
                      Instant timestamp, long sequenceNo) {
        this.type       = type;
        this.source     = source;
        this.data       = data;
        this.extra      = extra;
        this.headers    = headers != null ? Map.copyOf(headers) : Map.of();
        this.statusCode = statusCode;
        this.durationMs = durationMs;
        this.timestamp  = timestamp;
        this.sequenceNo = sequenceNo;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Type                getType()       { return type; }
    public String              getSource()     { return source; }
    public String              getData()       { return data; }
    public String              getExtra()      { return extra; }
    public Map<String, String> getHeaders()    { return headers; }
    public int                 getStatusCode() { return statusCode; }
    public long                getDurationMs() { return durationMs; }
    public Instant             getTimestamp()  { return timestamp; }
    public long                getSequenceNo() { return sequenceNo; }

    // ── Convenience ────────────────────────────────────────────────────────────

    public boolean isNetworkCapture()   { return type == Type.NETWORK_REQUEST || type == Type.NETWORK_RESPONSE; }
    public boolean isStorageCapture()   { return type == Type.STORAGE_SET || type == Type.STORAGE_REMOVE; }
    public boolean isConsoleCapture()   { return type == Type.CONSOLE_LOG; }
    public boolean isDomCapture()       { return type == Type.DOM_MUTATION; }
    public boolean isErrorResponse()    { return type == Type.NETWORK_RESPONSE && statusCode >= 400; }
    public boolean isServerError()      { return type == Type.NETWORK_RESPONSE && statusCode >= 500; }

    /** Returns true if this capture's source/data contains the given substring (case-insensitive). */
    public boolean contains(String text) {
        String lower = text.toLowerCase();
        return (source != null && source.toLowerCase().contains(lower)) ||
               (data   != null && data.toLowerCase().contains(lower));
    }

    @Override
    public String toString() {
        return switch (type) {
            case NETWORK_REQUEST  -> String.format("SPY[REQ  ] %s %s", extra, source);
            case NETWORK_RESPONSE -> String.format("SPY[RESP ] %d %s (%dms) %db",
                    statusCode, source, durationMs, data != null ? data.length() : 0);
            case STORAGE_SET      -> String.format("SPY[STORE] SET %s = %s", source, data);
            case STORAGE_REMOVE   -> String.format("SPY[STORE] DEL %s", source);
            case COOKIE_CHANGE    -> String.format("SPY[COOK ] %s", source);
            case DOM_MUTATION     -> String.format("SPY[DOM  ] %s on %s", extra, source);
            case CONSOLE_LOG      -> String.format("SPY[CONS ] [%s] %s", extra, data);
            case JS_VARIABLE      -> String.format("SPY[JSVAR] %s = %s", source, data);
            case CUSTOM_EVENT     -> String.format("SPY[EVT  ] %s: %s", source, data);
        };
    }
}
