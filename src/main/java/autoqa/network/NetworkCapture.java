package autoqa.network;

import java.time.Instant;

/**
 * Immutable snapshot of a single captured network request/response pair.
 */
public class NetworkCapture {

    public enum Type { REQUEST, RESPONSE, FAILED }

    private final Type    type;
    private final String  requestId;
    private final String  url;
    private final String  method;
    private final int     statusCode;       // 0 for REQUEST type
    private final String  mimeType;
    private final long    responseSizeBytes;
    private final long    durationMs;
    private final Instant timestamp;
    private final String  failureReason;    // non-null for FAILED type

    public NetworkCapture(Type type, String requestId, String url, String method,
                          int statusCode, String mimeType, long responseSizeBytes,
                          long durationMs, Instant timestamp, String failureReason) {
        this.type              = type;
        this.requestId         = requestId;
        this.url               = url;
        this.method            = method;
        this.statusCode        = statusCode;
        this.mimeType          = mimeType;
        this.responseSizeBytes = responseSizeBytes;
        this.durationMs        = durationMs;
        this.timestamp         = timestamp;
        this.failureReason     = failureReason;
    }

    /** Factory for a captured request. */
    public static NetworkCapture request(String requestId, String url, String method) {
        return new NetworkCapture(Type.REQUEST, requestId, url, method, 0, null, 0, 0,
                Instant.now(), null);
    }

    /** Factory for a captured response. */
    public static NetworkCapture response(String requestId, String url, String method,
                                          int statusCode, String mimeType,
                                          long sizeBytes, long durationMs) {
        return new NetworkCapture(Type.RESPONSE, requestId, url, method,
                statusCode, mimeType, sizeBytes, durationMs, Instant.now(), null);
    }

    /** Factory for a failed request. */
    public static NetworkCapture failed(String requestId, String url, String method, String reason) {
        return new NetworkCapture(Type.FAILED, requestId, url, method, 0, null, 0, 0,
                Instant.now(), reason);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Type    getType()              { return type; }
    public String  getRequestId()         { return requestId; }
    public String  getUrl()              { return url; }
    public String  getMethod()           { return method; }
    public int     getStatusCode()       { return statusCode; }
    public String  getMimeType()         { return mimeType; }
    public long    getResponseSizeBytes(){ return responseSizeBytes; }
    public long    getDurationMs()       { return durationMs; }
    public Instant getTimestamp()        { return timestamp; }
    public String  getFailureReason()    { return failureReason; }

    public boolean isRequest()  { return type == Type.REQUEST; }
    public boolean isResponse() { return type == Type.RESPONSE; }
    public boolean isFailed()   { return type == Type.FAILED; }
    public boolean isSuccess()  { return type == Type.RESPONSE && statusCode >= 200 && statusCode < 300; }
    public boolean isError()    { return type == Type.RESPONSE && statusCode >= 400; }

    /** Returns true if the URL matches the given pattern (regex). */
    public boolean urlMatches(String urlPattern) {
        return url != null && url.matches(".*" + urlPattern + ".*");
    }

    @Override
    public String toString() {
        return type == Type.RESPONSE
                ? String.format("NetworkCapture{%s %s %d %dms}", method, url, statusCode, durationMs)
                : String.format("NetworkCapture{%s %s %s}", type, method, url);
    }
}
