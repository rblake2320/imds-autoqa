package autoqa.spy;

import org.testng.annotations.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SpyCapture} and {@link ApplicationSpy} data model / assertions.
 * No browser required — captures are injected manually.
 */
public class ApplicationSpyTest {

    private ApplicationSpy spyWith(SpyCapture... captures) {
        // Package-visible constructor for testing
        ApplicationSpy spy = ApplicationSpy.attach(null);
        for (SpyCapture c : captures) {
            spy.captures().size(); // triggers lazy init — use drain path instead
        }
        // Directly add to the public captures list via the test helper
        return new TestSpy(captures);
    }

    // ── SpyCapture tests ──────────────────────────────────────────────────────

    @Test
    public void spyCapture_networkRequest_isCorrectlyTyped() {
        SpyCapture c = new SpyCapture(SpyCapture.Type.NETWORK_REQUEST,
                "https://api.example.com/login", "{\"user\":\"bob\"}", "POST",
                null, 0, 0, Instant.now(), 1L);
        assertThat(c.isNetworkCapture()).isTrue();
        assertThat(c.isErrorResponse()).isFalse();
        assertThat(c.getType()).isEqualTo(SpyCapture.Type.NETWORK_REQUEST);
    }

    @Test
    public void spyCapture_serverError_isDetected() {
        SpyCapture c = new SpyCapture(SpyCapture.Type.NETWORK_RESPONSE,
                "https://api.example.com/data", "Internal Server Error", "GET",
                null, 500, 200, Instant.now(), 2L);
        assertThat(c.isServerError()).isTrue();
        assertThat(c.isErrorResponse()).isTrue();
    }

    @Test
    public void spyCapture_contains_isCaseInsensitive() {
        SpyCapture c = new SpyCapture(SpyCapture.Type.NETWORK_RESPONSE,
                "https://api.example.com/checkout", "{\"orderId\":\"123\"}", "POST",
                null, 200, 150, Instant.now(), 3L);
        assertThat(c.contains("orderId")).isTrue();
        assertThat(c.contains("ORDERID")).isTrue();
        assertThat(c.contains("userId")).isFalse();
    }

    @Test
    public void spyCapture_toString_includesKeyInfo() {
        SpyCapture c = new SpyCapture(SpyCapture.Type.NETWORK_RESPONSE,
                "https://api.example.com/data", "ok", "GET",
                null, 200, 50, Instant.now(), 4L);
        assertThat(c.toString()).contains("200").contains("api.example.com");
    }

    @Test
    public void spyCapture_headers_areImmutable() {
        SpyCapture c = new SpyCapture(SpyCapture.Type.NETWORK_REQUEST,
                "https://api.example.com", "", "GET",
                Map.of("Authorization", "Bearer token"), 0, 0, Instant.now(), 5L);
        assertThat(c.getHeaders()).containsKey("Authorization");
        assertThatThrownBy(() -> c.getHeaders().put("X-New", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    public void spyCapture_storageType_isCorrectlyIdentified() {
        SpyCapture c = new SpyCapture(SpyCapture.Type.STORAGE_SET,
                "authToken", "eyJhbGc...", "local",
                null, 0, 0, Instant.now(), 6L);
        assertThat(c.isStorageCapture()).isTrue();
        assertThat(c.isNetworkCapture()).isFalse();
    }

    // ── TestSpy helper ────────────────────────────────────────────────────────

    /** Test-only ApplicationSpy with manually pre-loaded captures. */
    private static class TestSpy extends ApplicationSpy {
        private final java.util.List<SpyCapture> testCaptures;

        TestSpy(SpyCapture... caps) {
            super(null);
            this.testCaptures = new java.util.ArrayList<>(java.util.Arrays.asList(caps));
        }

        @Override
        public List<SpyCapture> captures() {
            return java.util.Collections.unmodifiableList(testCaptures);
        }

        @Override
        public List<SpyCapture> captures(SpyCapture.Type type) {
            return testCaptures.stream().filter(c -> c.getType() == type).toList();
        }

        @Override
        public List<SpyCapture> capturing(String text) {
            return testCaptures.stream().filter(c -> c.contains(text)).toList();
        }
    }

    // ── ApplicationSpy assertion tests using TestSpy ──────────────────────────

    @Test
    public void assertNoServerErrors_passesWithNoServerErrors() {
        TestSpy spy = new TestSpy(
                new SpyCapture(SpyCapture.Type.NETWORK_RESPONSE, "https://a.com/api", "ok", "GET",
                        null, 200, 50, Instant.now(), 0L)
        );
        spy.assertNoServerErrors(); // should not throw
    }

    @Test
    public void assertNoServerErrors_throwsOnServerError() {
        TestSpy spy = new TestSpy(
                new SpyCapture(SpyCapture.Type.NETWORK_RESPONSE, "https://a.com/api", "fail", "GET",
                        null, 500, 50, Instant.now(), 0L)
        );
        assertThatThrownBy(spy::assertNoServerErrors)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("500");
    }

    @Test
    public void defaultPollMs_isPositive() {
        assertThat(ApplicationSpy.DEFAULT_POLL_MS).isGreaterThan(0);
    }

    @Test
    public void defaultMaxBodyChars_isPositive() {
        assertThat(ApplicationSpy.DEFAULT_MAX_BODY_CHARS).isGreaterThan(0);
    }
}
