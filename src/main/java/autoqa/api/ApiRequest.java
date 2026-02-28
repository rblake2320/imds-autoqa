package autoqa.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable-ish builder for HTTP requests sent through {@link ApiClient}.
 *
 * <pre>{@code
 * ApiRequest req = ApiRequest.post("https://api.example.com/users", "{\"name\":\"Alice\"}")
 *         .contentType("application/json")
 *         .bearerAuth("my-token")
 *         .header("X-Request-Id", "abc123")
 *         .timeout(5_000);
 * }</pre>
 */
public final class ApiRequest {

    /** Supported HTTP methods. */
    public enum Method {
        GET, POST, PUT, DELETE, PATCH
    }

    // ── fields ─────────────────────────────────────────────────────────────

    private final Method method;
    private final String url;
    private final Map<String, String> headers;
    private final Map<String, String> queryParams;
    private final String body;
    private final String contentType;
    private final int timeoutMs;

    // ── private constructor (use builder / factory methods) ────────────────

    private ApiRequest(Builder b) {
        this.method      = Objects.requireNonNull(b.method,  "method must not be null");
        this.url         = Objects.requireNonNull(b.url,     "url must not be null");
        this.headers     = Collections.unmodifiableMap(new LinkedHashMap<>(b.headers));
        this.queryParams = Collections.unmodifiableMap(new LinkedHashMap<>(b.queryParams));
        this.body        = b.body;
        this.contentType = b.contentType;
        this.timeoutMs   = b.timeoutMs;
    }

    // ── static factory methods ─────────────────────────────────────────────

    /** Creates a GET request builder for {@code url}. */
    public static Builder get(String url) {
        return new Builder(Method.GET, url);
    }

    /** Creates a POST request builder with a request body. */
    public static Builder post(String url, String body) {
        return new Builder(Method.POST, url).body(body);
    }

    /** Creates a PUT request builder with a request body. */
    public static Builder put(String url, String body) {
        return new Builder(Method.PUT, url).body(body);
    }

    /** Creates a DELETE request builder for {@code url}. */
    public static Builder delete(String url) {
        return new Builder(Method.DELETE, url);
    }

    /** Creates a PATCH request builder with a request body. */
    public static Builder patch(String url, String body) {
        return new Builder(Method.PATCH, url).body(body);
    }

    // ── accessors ──────────────────────────────────────────────────────────

    public Method getMethod()                  { return method; }
    public String getUrl()                     { return url; }
    public Map<String, String> getHeaders()    { return headers; }
    public Map<String, String> getQueryParams(){ return queryParams; }
    public String getBody()                    { return body; }
    public String getContentType()             { return contentType; }
    public int getTimeoutMs()                  { return timeoutMs; }

    // ── builder ────────────────────────────────────────────────────────────

    /**
     * Fluent builder returned by all static factory methods.
     * Calling {@link #build()} is optional — {@link ApiClient#send(ApiRequest)} accepts
     * both a {@code Builder} (via {@link #build()}) and a fully-built {@code ApiRequest}.
     */
    public static final class Builder {

        private final Method method;
        private final String url;
        private final Map<String, String> headers     = new LinkedHashMap<>();
        private final Map<String, String> queryParams = new LinkedHashMap<>();
        private String body;
        private String contentType;
        private int    timeoutMs = 30_000;

        private Builder(Method method, String url) {
            this.method = method;
            this.url    = url;
        }

        /** Adds (or replaces) a request header. */
        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        /** Sets the {@code Authorization: Bearer <token>} header. */
        public Builder bearerAuth(String token) {
            return header("Authorization", "Bearer " + token);
        }

        /**
         * Sets the {@code Authorization: Basic <base64>} header.
         * Credentials are encoded as {@code user:pass} in UTF-8.
         */
        public Builder basicAuth(String user, String pass) {
            String credentials = user + ":" + pass;
            String encoded = Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            return header("Authorization", "Basic " + encoded);
        }

        /** Adds a URL query parameter. */
        public Builder param(String name, String value) {
            queryParams.put(name, value);
            return this;
        }

        /** Sets the {@code Content-Type} header value. */
        public Builder contentType(String ct) {
            this.contentType = ct;
            return this;
        }

        /** Sets the request timeout in milliseconds (default 30 000). */
        public Builder timeout(int ms) {
            this.timeoutMs = ms;
            return this;
        }

        /** Sets the raw request body. */
        private Builder body(String body) {
            this.body = body;
            return this;
        }

        /** Builds and returns an {@link ApiRequest}. */
        public ApiRequest build() {
            return new ApiRequest(this);
        }
    }
}
