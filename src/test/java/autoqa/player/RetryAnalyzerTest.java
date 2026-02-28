package autoqa.player;

import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RetryAnalyzer}.
 */
public class RetryAnalyzerTest {

    @Test
    public void maxAttempts_isPositive() {
        assertThat(RetryAnalyzer.getMaxAttempts()).isGreaterThan(0);
    }

    @Test
    public void delayMs_isNonNegative() {
        assertThat(RetryAnalyzer.getDelayMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    public void newAnalyzer_hasZeroRetryCount() {
        RetryAnalyzer analyzer = new RetryAnalyzer();
        assertThat(analyzer.getRetryCount()).isEqualTo(0);
    }
}
