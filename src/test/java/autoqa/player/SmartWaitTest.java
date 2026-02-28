package autoqa.player;

import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for non-browser static utilities in {@link SmartWait}.
 * Browser-dependent wait methods require a live WebDriver and are tested
 * separately in integration tests.
 */
public class SmartWaitTest {

    @Test
    public void defaultTimeout_isPositive() {
        assertThat(SmartWait.DEFAULT_TIMEOUT_SEC).isGreaterThan(0);
    }

    @Test
    public void defaultPollInterval_isPositive() {
        assertThat(SmartWait.DEFAULT_POLL_MS).isGreaterThan(0);
    }

    @Test
    public void defaultPollInterval_lessThanTimeout() {
        assertThat(SmartWait.DEFAULT_POLL_MS)
                .isLessThan(SmartWait.DEFAULT_TIMEOUT_SEC * 1000);
    }
}
