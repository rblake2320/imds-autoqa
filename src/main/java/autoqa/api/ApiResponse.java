package autoqa.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable value object representing the HTTP response returned by {@link ApiClient#send}.
 *
 * <p>JSON helpers use a lazily-initialised shared {@link ObjectMapper}; callers need not
 * manage their own instance.</p>
 */
public final class ApiResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── fields ─────────────────────────────────────────────────────────────

    private final int                 statusCode;
    private final String              body;
    private final Map<String, String> headers;
    private final long                durationMs;
    private final ApiRequest          request;

    // ── constructor ────────────────────────────────────────────────────────

    public ApiResponse(int statusCode,
                       String body,
                       Map<String, String> headers,
                       long durationMs,
                       ApiRequest request) {
        this.statusCode = statusCode;
        this.body       = body == null ? "" : body;
        this.headers    = headers == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(headers);
        this.durationMs = durationMs;
        this.request    = request;
    }

    // ── accessors ──────────────────────────────────────────────────────────

    /** HTTP status code (e.g. 200, 201, 404). */
    public int getStatusCode()                  { return statusCode; }

    /** Raw response body as a String (never null; empty string if absent). */
    public String getBody()                     { return body; }

    /** Response headers (lowercase-normalised names). */
    public Map<String, String> getHeaders()     { return headers; }

    /** Wall-clock time in milliseconds from request dispatch to full response read. */
    public long getDurationMs()                 { return durationMs; }

    /** The originating {@link ApiRequest}. */
    public ApiRequest getRequest()              { return request; }

    // ── convenience ────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the status code is in the 2xx range (200–299 inclusive).
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode <= 299;
    }

    /**
     * Returns {@code true} when {@code text} appears anywhere in the response body
     * (case-insensitive).
     */
    public boolean bodyContains(String text) {
        if (text == null) return false;
        return body.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
    }

    // ── JSON helpers ───────────────────────────────────────────────────────

    /**
     * Parses the response body as JSON and returns the root {@link JsonNode}.
     *
     * @throws RuntimeException wrapping {@link com.fasterxml.jackson.core.JsonProcessingException}
     *                          if the body is not valid JSON.
     */
    public JsonNode getJsonNode() {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse response body as JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Navigates a dot-notation JSON path and returns the matching node's text value,
     * or {@code null} if any segment along the path is missing or not a value node.
     *
     * <p>Example: for body {@code {"data":{"user":{"id":42}}}} the call
     * {@code getJsonValue("data.user.id")} returns {@code "42"}.</p>
     *
     * @param jsonPath dot-separated field names, e.g. {@code "data.user.id"}
     * @return string representation of the terminal value, or {@code null} if not found
     */
    public String getJsonValue(String jsonPath) {
        if (jsonPath == null || jsonPath.isEmpty()) return null;
        try {
            JsonNode node = getJsonNode();
            String[] segments = jsonPath.split("\\.");
            for (String segment : segments) {
                if (node == null || node.isMissingNode() || !node.isObject()) return null;
                node = node.get(segment);
            }
            if (node == null || node.isMissingNode()) return null;
            // Return raw text for value nodes; toString (JSON-encoded) for containers.
            return node.isValueNode() ? node.asText() : node.toString();
        } catch (Exception e) {
            return null;
        }
    }

    // ── toString ───────────────────────────────────────────────────────────

    @Override
    public String toString() {
        return "ApiResponse{status=" + statusCode
                + ", durationMs=" + durationMs
                + ", bodyLength=" + body.length() + '}';
    }
}
