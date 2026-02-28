package autoqa.api;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Synchronous HTTP client built on top of OkHttp 4.
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * ApiClient client = ApiClient.create();
 *
 * ApiResponse response = client.send(
 *     ApiRequest.get("https://api.example.com/users")
 *               .bearerAuth("secret")
 *               .param("page", "1")
 *               .timeout(5_000)
 *               .build());
 *
 * client.assertThat(response)
 *       .statusCode(200)
 *       .bodyContains("Alice")
 *       .durationBelow(3_000);
 * }</pre>
 *
 * <p>Each {@code ApiClient} instance owns a single {@link OkHttpClient}; the underlying
 * connection pool is shared and should be reused across tests in the same suite.</p>
 */
public final class ApiClient {

    private static final Logger LOG = LoggerFactory.getLogger(ApiClient.class);

    /** Reusable {@code null} body for methods that have no payload. */
    private static final RequestBody EMPTY_BODY =
            RequestBody.create(new byte[0], null);

    private final OkHttpClient http;

    // ── constructors / factory ─────────────────────────────────────────────

    private ApiClient(OkHttpClient http) {
        this.http = Objects.requireNonNull(http, "OkHttpClient must not be null");
    }

    /**
     * Creates an {@code ApiClient} with a default {@link OkHttpClient}.
     * The per-request timeout set on {@link ApiRequest} overrides the client-level defaults.
     */
    public static ApiClient create() {
        OkHttpClient base = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        return new ApiClient(base);
    }

    /**
     * Creates an {@code ApiClient} wrapping a pre-configured {@link OkHttpClient}.
     * Useful when tests need to inject a mocked or instrumented client.
     */
    public static ApiClient wrap(OkHttpClient http) {
        return new ApiClient(http);
    }

    // ── send ──────────────────────────────────────────────────────────────

    /**
     * Executes the request synchronously and returns an {@link ApiResponse}.
     *
     * <p>The per-request timeout from {@link ApiRequest#getTimeoutMs()} is applied by
     * building a new {@link OkHttpClient} via {@link OkHttpClient#newBuilder()} (which
     * shares the underlying connection pool and dispatcher).</p>
     *
     * @param request the API request to execute
     * @return a fully-populated {@link ApiResponse}
     * @throws RuntimeException wrapping {@link IOException} on transport-level errors
     */
    public ApiResponse send(ApiRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // Apply per-request timeout via OkHttp's newBuilder() (shares pool/dispatcher).
        int timeoutMs = request.getTimeoutMs();
        OkHttpClient callClient = http.newBuilder()
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();

        Request okRequest = buildOkRequest(request);

        LOG.debug(">> {} {}", request.getMethod(), okRequest.url());

        long start = System.currentTimeMillis();
        try (Response okResponse = callClient.newCall(okRequest).execute()) {
            long durationMs = System.currentTimeMillis() - start;

            ResponseBody responseBody = okResponse.body();
            String bodyString = (responseBody != null) ? responseBody.string() : "";

            // Collect all response headers (last value wins for duplicates).
            Map<String, String> headers = new LinkedHashMap<>();
            for (String name : okResponse.headers().names()) {
                headers.put(name.toLowerCase(), okResponse.header(name));
            }

            LOG.debug("<< {} {} ({}ms)", okResponse.code(), okRequest.url(), durationMs);

            return new ApiResponse(
                    okResponse.code(),
                    bodyString,
                    headers,
                    durationMs,
                    request);

        } catch (IOException e) {
            throw new RuntimeException(
                    "HTTP request failed [" + request.getMethod() + " " + request.getUrl() + "]: "
                            + e.getMessage(), e);
        }
    }

    // ── assertThat ────────────────────────────────────────────────────────

    /**
     * Returns a fluent {@link ApiAssertion} chain for the given response.
     *
     * <pre>{@code
     * client.assertThat(response).statusCode(200).bodyContains("ok");
     * }</pre>
     */
    public ApiAssertion assertThat(ApiResponse response) {
        return new ApiAssertion(response);
    }

    // ── internal helpers ──────────────────────────────────────────────────

    /**
     * Translates an {@link ApiRequest} into an OkHttp {@link Request}, handling:
     * <ul>
     *   <li>URL query parameters</li>
     *   <li>Custom and content-type headers</li>
     *   <li>Request bodies for POST / PUT / PATCH (and empty body for DELETE)</li>
     * </ul>
     */
    private static Request buildOkRequest(ApiRequest request) {

        // ── URL with query params ──────────────────────────────────────────
        HttpUrl.Builder urlBuilder = HttpUrl.parse(request.getUrl()).newBuilder();
        if (urlBuilder == null) {
            throw new IllegalArgumentException("Malformed URL: " + request.getUrl());
        }
        request.getQueryParams().forEach(urlBuilder::addQueryParameter);

        // ── OkHttp request builder ─────────────────────────────────────────
        Request.Builder builder = new Request.Builder().url(urlBuilder.build());

        // ── headers ───────────────────────────────────────────────────────
        request.getHeaders().forEach(builder::addHeader);

        // ── body / method ─────────────────────────────────────────────────
        String rawBody      = request.getBody();
        String ct           = request.getContentType();
        MediaType mediaType = (ct != null) ? MediaType.parse(ct) : null;

        switch (request.getMethod()) {

            case GET:
                builder.get();
                break;

            case DELETE:
                // DELETE may legitimately carry a body; send empty body if none provided.
                if (rawBody != null && !rawBody.isEmpty()) {
                    builder.delete(RequestBody.create(rawBody, mediaType));
                } else {
                    builder.delete();
                }
                break;

            case POST:
                builder.post(toBody(rawBody, mediaType));
                break;

            case PUT:
                builder.put(toBody(rawBody, mediaType));
                break;

            case PATCH:
                builder.patch(toBody(rawBody, mediaType));
                break;

            default:
                throw new UnsupportedOperationException(
                        "Unsupported HTTP method: " + request.getMethod());
        }

        return builder.build();
    }

    /**
     * Converts a nullable body string + media type to an {@link RequestBody}.
     * If {@code rawBody} is null or empty, returns an empty body so OkHttp is happy.
     */
    private static RequestBody toBody(String rawBody, MediaType mediaType) {
        if (rawBody == null || rawBody.isEmpty()) {
            return EMPTY_BODY;
        }
        return RequestBody.create(rawBody, mediaType);
    }
}
