package autoqa.network;

import java.util.List;

/**
 * Fluent assertion builder for {@link NetworkMonitor} captures.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * monitor.assertThat()
 *     .hasRequestTo("api/login")
 *     .hasNoServerErrors()
 *     .hasResponseBelow("api/data", 300)
 *     .hasAtLeast(5).requests();
 * }</pre>
 */
public class NetworkAssertion {

    private final NetworkMonitor monitor;

    NetworkAssertion(NetworkMonitor monitor) {
        this.monitor = monitor;
    }

    /** Asserts at least one request matched the URL pattern. */
    public NetworkAssertion hasRequestTo(String urlPattern) {
        monitor.assertRequested(urlPattern);
        return this;
    }

    /** Asserts no request matched the URL pattern. */
    public NetworkAssertion hasNoRequestTo(String urlPattern) {
        monitor.assertNotRequested(urlPattern);
        return this;
    }

    /** Asserts no HTTP 5xx server errors. */
    public NetworkAssertion hasNoServerErrors() {
        monitor.assertNoServerErrors();
        return this;
    }

    /** Asserts no HTTP 4xx or 5xx errors. */
    public NetworkAssertion hasNoErrors() {
        monitor.assertNoErrors();
        return this;
    }

    /** Asserts all matching responses completed within maxMs. */
    public NetworkAssertion hasResponseBelow(String urlPattern, long maxMs) {
        monitor.assertResponseBelow(urlPattern, maxMs);
        return this;
    }

    /** Asserts the total capture count is at least minCount. */
    public NetworkAssertion hasAtLeastCaptures(int minCount) {
        int actual = monitor.captures().size();
        if (actual < minCount) {
            throw new AssertionError(String.format(
                    "NetworkAssertion: expected ≥%d captures but got %d", minCount, actual));
        }
        return this;
    }

    /** Asserts that the total count of server errors is at most maxErrors. */
    public NetworkAssertion hasAtMostServerErrors(int maxErrors) {
        int count = monitor.serverErrors().size();
        if (count > maxErrors) {
            throw new AssertionError(String.format(
                    "NetworkAssertion: expected ≤%d server errors but got %d", maxErrors, count));
        }
        return this;
    }

    /**
     * Asserts that the number of requests matching the pattern equals exactly {@code expected}.
     */
    public NetworkAssertion hasExactRequestCount(String urlPattern, int expected) {
        int actual = monitor.capturesMatching(urlPattern).size();
        if (actual != expected) {
            throw new AssertionError(String.format(
                    "NetworkAssertion: expected exactly %d request(s) to '%s' but got %d",
                    expected, urlPattern, actual));
        }
        return this;
    }

    /** Returns the underlying monitor for direct access. */
    public NetworkMonitor monitor() { return monitor; }

    /** Returns a summary string. */
    @Override
    public String toString() { return "NetworkAssertion[" + monitor.summary() + "]"; }
}
