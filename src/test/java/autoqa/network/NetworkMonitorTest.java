package autoqa.network;

import org.testng.annotations.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link NetworkMonitor}, {@link NetworkCapture}, and {@link NetworkAssertion}.
 * No browser required — tests use manual recording.
 */
public class NetworkMonitorTest {

    // ── Helper: build a monitor with pre-loaded captures ─────────────────────

    private NetworkMonitor monitorWith(NetworkCapture... captures) {
        // Create a monitor with a stub CDP (unavailable in unit tests).
        // We test through the public record() + assertion API.
        NetworkMonitor monitor = createStubMonitor();
        for (NetworkCapture c : captures) {
            monitor.record(c);
        }
        return monitor;
    }

    /**
     * Creates a monitor that bypasses the CDP requirement for unit testing.
     * We use a private subclass trick by extending NetworkMonitor is not possible (it's not
     * extendable), so instead we test through the accessible record() method.
     *
     * Note: we need a HasCdp — but we can just pass null and not call start()/stop()
     * in unit tests since those require a live browser.
     */
    private NetworkMonitor createStubMonitor() {
        // NetworkMonitor.attach requires a HasCdp driver, which we can't mock without
        // Mockito in this scope. Instead, we test the static data model and assertion logic
        // via direct capture recording using reflection or a package-visible constructor.
        // For this unit test, we verify capture data model correctness:
        return new NetworkMonitor(null, null) {
            @Override
            public NetworkMonitor start() { return this; }
            @Override
            public NetworkMonitor stop()  { return this; }
        };
    }

    // ── NetworkCapture tests ──────────────────────────────────────────────────

    @Test
    public void request_factory_setsCorrectType() {
        NetworkCapture c = NetworkCapture.request("req1", "https://api.example.com/login", "POST");
        assertThat(c.isRequest()).isTrue();
        assertThat(c.getUrl()).isEqualTo("https://api.example.com/login");
        assertThat(c.getMethod()).isEqualTo("POST");
        assertThat(c.getStatusCode()).isEqualTo(0);
    }

    @Test
    public void response_factory_setsCorrectFields() {
        NetworkCapture c = NetworkCapture.response("req2", "https://api.example.com/data",
                "GET", 200, "application/json", 1024L, 150L);
        assertThat(c.isResponse()).isTrue();
        assertThat(c.isSuccess()).isTrue();
        assertThat(c.getStatusCode()).isEqualTo(200);
        assertThat(c.getDurationMs()).isEqualTo(150L);
        assertThat(c.getMimeType()).isEqualTo("application/json");
    }

    @Test
    public void failed_factory_setsFailureReason() {
        NetworkCapture c = NetworkCapture.failed("req3", "https://api.example.com/fail",
                "GET", "ERR_CONNECTION_REFUSED");
        assertThat(c.isFailed()).isTrue();
        assertThat(c.getFailureReason()).isEqualTo("ERR_CONNECTION_REFUSED");
    }

    @Test
    public void error_response_identifiedCorrectly() {
        NetworkCapture c404 = NetworkCapture.response("r1", "https://example.com/missing",
                "GET", 404, "text/html", 0, 50);
        NetworkCapture c500 = NetworkCapture.response("r2", "https://example.com/error",
                "GET", 500, "text/html", 0, 100);

        assertThat(c404.isError()).isTrue();
        assertThat(c500.isError()).isTrue();
        assertThat(c500.isSuccess()).isFalse();
    }

    @Test
    public void urlMatches_worksWithPartialPattern() {
        NetworkCapture c = NetworkCapture.response("r1", "https://api.example.com/users/123",
                "GET", 200, "application/json", 512, 80);
        assertThat(c.urlMatches("api.example.com")).isTrue();
        assertThat(c.urlMatches("users/\\d+")).isTrue();
        assertThat(c.urlMatches("orders")).isFalse();
    }

    // ── NetworkAssertion tests ────────────────────────────────────────────────

    @Test
    public void assertRequested_passesWhenMatchFound() {
        NetworkMonitor monitor = monitorWith(
                NetworkCapture.response("r1", "https://api.example.com/login", "POST", 200, null, 0, 50)
        );
        // Use a regex-safe pattern (no special chars) — the urlMatches method wraps in .*pattern.*
        monitor.assertRequested("api\\.example\\.com/login");
    }

    @Test
    public void assertRequested_throwsWhenNoMatch() {
        NetworkMonitor monitor = monitorWith(
                NetworkCapture.response("r1", "https://api.example.com/home", "GET", 200, null, 0, 50)
        );
        assertThatThrownBy(() -> monitor.assertRequested("api-login-endpoint"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("api-login-endpoint");
    }

    @Test
    public void assertNoServerErrors_passesWhenNoErrors() {
        NetworkMonitor monitor = monitorWith(
                NetworkCapture.response("r1", "https://example.com/api", "GET", 200, null, 0, 50),
                NetworkCapture.response("r2", "https://example.com/data", "GET", 201, null, 0, 30)
        );
        monitor.assertNoServerErrors(); // should not throw
    }

    @Test
    public void assertNoServerErrors_throwsWhenServerError() {
        NetworkMonitor monitor = monitorWith(
                NetworkCapture.response("r1", "https://example.com/api", "GET", 500, null, 0, 50)
        );
        assertThatThrownBy(monitor::assertNoServerErrors)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("500");
    }

    @Test
    public void assertResponseBelow_passesWhenFast() {
        NetworkMonitor monitor = monitorWith(
                NetworkCapture.response("r1", "https://example.com/api/search", "GET", 200, null, 0, 200)
        );
        monitor.assertResponseBelow("api/search", 500); // 200ms < 500ms threshold
    }

    @Test
    public void assertResponseBelow_throwsWhenSlow() {
        NetworkMonitor monitor = monitorWith(
                NetworkCapture.response("r1", "https://example.com/api/search", "GET", 200, null, 0, 800)
        );
        assertThatThrownBy(() -> monitor.assertResponseBelow("api/search", 500))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("800");
    }

    @Test
    public void captures_returnsAllRecorded() {
        NetworkMonitor monitor = monitorWith(
                NetworkCapture.request("r1", "https://a.com", "GET"),
                NetworkCapture.response("r2", "https://b.com", "POST", 201, null, 0, 10)
        );
        assertThat(monitor.captures()).hasSize(2);
        assertThat(monitor.responses()).hasSize(1);
    }

    @Test
    public void summary_returnsNonEmptyString() {
        NetworkMonitor monitor = monitorWith(
                NetworkCapture.response("r1", "https://a.com/api", "GET", 200, null, 512, 100)
        );
        String summary = monitor.summary();
        assertThat(summary).contains("NetworkMonitor");
    }
}
