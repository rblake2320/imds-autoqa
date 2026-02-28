package autoqa.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Fluent assertion chain for {@link ApiResponse} — similar in style to RestAssured's
 * {@code .then().statusCode(200).body(...)}.
 *
 * <p>All assertion methods throw {@link AssertionError} on failure so they can be used
 * inside any assertion-aware test framework (TestNG, JUnit, plain Java).</p>
 *
 * <pre>{@code
 * ApiClient.create()
 *     .assertThat(response)
 *     .statusCode(200)
 *     .bodyContains("userId")
 *     .durationBelow(2_000);
 * }</pre>
 */
public final class ApiAssertion {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApiResponse response;

    // ── constructor ────────────────────────────────────────────────────────

    /**
     * Creates an assertion chain over {@code response}.
     *
     * @param response must not be null
     */
    public ApiAssertion(ApiResponse response) {
        this.response = Objects.requireNonNull(response, "response must not be null");
    }

    // ── status assertions ──────────────────────────────────────────────────

    /**
     * Asserts that the HTTP status code equals {@code expected}.
     *
     * @throws AssertionError with a descriptive message on mismatch
     */
    public ApiAssertion statusCode(int expected) {
        int actual = response.getStatusCode();
        if (actual != expected) {
            throw new AssertionError(
                    "Expected status code <" + expected + "> but was <" + actual + ">."
                            + "\n  URL: " + response.getRequest().getUrl()
                            + "\n  Body: " + truncate(response.getBody(), 300));
        }
        return this;
    }

    /**
     * Asserts that the HTTP status code is in the inclusive range [{@code lo}, {@code hi}].
     *
     * @throws AssertionError on mismatch
     */
    public ApiAssertion statusCodeBetween(int lo, int hi) {
        int actual = response.getStatusCode();
        if (actual < lo || actual > hi) {
            throw new AssertionError(
                    "Expected status code between <" + lo + "> and <" + hi + "> but was <" + actual + ">."
                            + "\n  URL: " + response.getRequest().getUrl());
        }
        return this;
    }

    // ── body assertions ────────────────────────────────────────────────────

    /**
     * Asserts that the response body contains {@code text} (case-insensitive).
     *
     * @throws AssertionError on mismatch
     */
    public ApiAssertion bodyContains(String text) {
        if (!response.bodyContains(text)) {
            throw new AssertionError(
                    "Expected body to contain <" + text + "> (case-insensitive)."
                            + "\n  Actual body: " + truncate(response.getBody(), 500));
        }
        return this;
    }

    /**
     * Asserts that the trimmed response body exactly equals {@code expected}.
     *
     * @throws AssertionError on mismatch
     */
    public ApiAssertion bodyEquals(String expected) {
        String actual = response.getBody().trim();
        String exp    = (expected == null ? "" : expected.trim());
        if (!actual.equals(exp)) {
            throw new AssertionError(
                    "Expected body to equal:\n  <" + exp + ">"
                            + "\n  but was:\n  <" + actual + ">");
        }
        return this;
    }

    /**
     * Asserts structural JSON equality between the response body and {@code expectedJson},
     * ignoring whitespace differences.  Uses Jackson's {@link JsonNode#equals} which
     * performs deep value comparison independent of field ordering in objects.
     *
     * @throws AssertionError if the bodies differ structurally or either is invalid JSON
     */
    public ApiAssertion bodyMatchesJson(String expectedJson) {
        try {
            JsonNode actualNode   = MAPPER.readTree(response.getBody());
            JsonNode expectedNode = MAPPER.readTree(expectedJson);
            if (!actualNode.equals(expectedNode)) {
                throw new AssertionError(
                        "JSON bodies do not match structurally."
                                + "\n  Expected: " + expectedNode
                                + "\n  Actual:   " + actualNode);
            }
        } catch (AssertionError ae) {
            throw ae;
        } catch (Exception e) {
            throw new AssertionError("Failed to parse JSON for comparison: " + e.getMessage(), e);
        }
        return this;
    }

    // ── header assertions ──────────────────────────────────────────────────

    /**
     * Asserts that the response contains a header named {@code name} with value
     * {@code expectedValue}.  Header name lookup is case-insensitive.
     *
     * @throws AssertionError if header is absent or its value does not match
     */
    public ApiAssertion header(String name, String expectedValue) {
        Map<String, String> headers = response.getHeaders();
        // find by case-insensitive name
        String actualValue = headers.entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);

        if (actualValue == null) {
            throw new AssertionError(
                    "Expected response header <" + name + "> to be present but it was missing."
                            + "\n  Available headers: " + headers.keySet());
        }
        if (!actualValue.equals(expectedValue)) {
            throw new AssertionError(
                    "Expected header <" + name + "> = <" + expectedValue + ">"
                            + " but was <" + actualValue + ">.");
        }
        return this;
    }

    // ── performance assertions ─────────────────────────────────────────────

    /**
     * Asserts that the response round-trip time was strictly less than {@code ms} milliseconds.
     *
     * @throws AssertionError if the actual duration is >= ms
     */
    public ApiAssertion durationBelow(long ms) {
        long actual = response.getDurationMs();
        if (actual >= ms) {
            throw new AssertionError(
                    "Expected response duration < " + ms + " ms but was " + actual + " ms.");
        }
        return this;
    }

    // ── internal helpers ───────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        if (s == null) return "<null>";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
