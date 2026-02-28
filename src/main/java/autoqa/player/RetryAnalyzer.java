package autoqa.player;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

import java.io.InputStream;
import java.util.Properties;

/**
 * TestNG {@link IRetryAnalyzer} that retries flaky tests automatically.
 *
 * <p>Use on individual tests or at suite level:
 * <pre>{@code
 * // Per-test annotation
 * @Test(retryAnalyzer = RetryAnalyzer.class)
 * public void myFlakyTest() { ... }
 * }</pre>
 *
 * <p>Configuration via {@code config.properties}:
 * <pre>
 * retry.max.attempts=3      # max retry count per test (default: 3)
 * retry.delay.ms=500        # delay between retries in ms (default: 500)
 * </pre>
 *
 * <p>Retry count resets per test method invocation. Failed tests retried up to
 * {@code max} times, with an optional delay. Results are logged and visible in
 * Allure reports (via AllureListener's {@code onTestRetry} hook).
 *
 * <p>Best used for:
 * <ul>
 *   <li>Network timeout / timing-sensitive UI tests</li>
 *   <li>Tests that depend on external services with occasional blips</li>
 *   <li>Browser animation / JavaScript rendering edge cases</li>
 * </ul>
 *
 * <p><strong>Do not use this as a substitute for fixing root causes.</strong>
 * Mark tests with {@code @Flaky} and use this sparingly for genuinely non-deterministic tests.
 */
public class RetryAnalyzer implements IRetryAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(RetryAnalyzer.class);

    private static final int  DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_DELAY_MS     = 500L;

    private static final int  MAX_ATTEMPTS;
    private static final long DELAY_MS;

    static {
        Properties props = new Properties();
        try (InputStream is = RetryAnalyzer.class.getResourceAsStream("/config.properties")) {
            if (is != null) props.load(is);
        } catch (Exception ignored) {}

        int maxAttempts;
        try {
            maxAttempts = Integer.parseInt(props.getProperty("retry.max.attempts",
                    String.valueOf(DEFAULT_MAX_ATTEMPTS)).trim());
        } catch (NumberFormatException e) {
            maxAttempts = DEFAULT_MAX_ATTEMPTS;
        }

        long delayMs;
        try {
            delayMs = Long.parseLong(props.getProperty("retry.delay.ms",
                    String.valueOf(DEFAULT_DELAY_MS)).trim());
        } catch (NumberFormatException e) {
            delayMs = DEFAULT_DELAY_MS;
        }

        MAX_ATTEMPTS = maxAttempts;
        DELAY_MS     = delayMs;
    }

    // Per-instance retry counter (one RetryAnalyzer instance per test method)
    private int retryCount = 0;

    @Override
    public boolean retry(ITestResult result) {
        if (retryCount < MAX_ATTEMPTS) {
            retryCount++;
            log.warn("Retrying test '{}' (attempt {}/{}) — failure: {}",
                    result.getName(), retryCount, MAX_ATTEMPTS,
                    result.getThrowable() != null ? result.getThrowable().getMessage() : "unknown");

            if (DELAY_MS > 0) {
                try {
                    Thread.sleep(DELAY_MS * retryCount); // back-off: multiply by attempt count
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        }

        log.error("Test '{}' failed after {} retry attempt(s) — giving up",
                result.getName(), MAX_ATTEMPTS);
        retryCount = 0; // Reset for next invocation (though usually a new instance is created)
        return false;
    }

    /** Returns the current retry attempt number (0 = first attempt). */
    public int getRetryCount() { return retryCount; }

    /** Returns the configured maximum retry attempts. */
    public static int getMaxAttempts() { return MAX_ATTEMPTS; }

    /** Returns the configured delay between retries in milliseconds. */
    public static long getDelayMs() { return DELAY_MS; }
}
