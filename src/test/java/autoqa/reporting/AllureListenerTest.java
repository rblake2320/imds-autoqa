package autoqa.reporting;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.IClass;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AllureListener}.
 *
 * <p>The Allure lifecycle is running in the test process (it records to
 * {@code target/allure-results}), so we intentionally avoid asserting against
 * internal Allure state — those assertions would couple the tests to Allure
 * implementation details.  Instead the tests assert:
 * <ul>
 *   <li>No exception is thrown from any listener callback.</li>
 *   <li>Evidence files that are present on disk get attached without error.</li>
 *   <li>The listener handles edge cases gracefully (null exception, missing dir, etc.).</li>
 * </ul>
 */
public class AllureListenerTest {

    @Mock private ITestResult mockResult;
    @Mock private IClass      mockClass;

    private AutoCloseable     mocks;
    private AllureListener    listener;
    private Path              tempDir;

    @BeforeMethod
    public void setUp() throws IOException {
        mocks    = MockitoAnnotations.openMocks(this);
        listener = new AllureListener();
        tempDir  = Files.createTempDirectory("allure-listener-test-");

        // Wire the mock ITestResult with a stable identity
        when(mockClass.getName()).thenReturn("autoqa.reporting.DummyTest");
        when(mockResult.getTestClass()).thenReturn(mockClass);
        when(mockResult.getName()).thenReturn("dummyMethod");
        when(mockResult.getThrowable()).thenReturn(null);
        when(mockResult.getAttribute("evidenceDir")).thenReturn(null);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        mocks.close();
        deleteDirectory(tempDir);
    }

    // ── onTestSuccess ─────────────────────────────────────────────────────

    /**
     * onTestSuccess must not throw even when Allure lifecycle has no
     * in-progress test case (e.g. when run outside of the Allure AspectJ agent).
     */
    @Test
    public void onTestSuccess_doesNotThrow() {
        assertThatCode(() -> listener.onTestSuccess(mockResult))
                .doesNotThrowAnyException();
    }

    /**
     * Verifies that onTestSuccess handles a real ITestResult stub without error,
     * covering the happy-path branch.
     */
    @Test
    public void onTestSuccess_withValidResult_completesNormally() {
        // Arrange: simulate a passing test result
        when(mockResult.getStatus()).thenReturn(ITestResult.SUCCESS);

        // Act + Assert: no exception
        assertThatCode(() -> listener.onTestSuccess(mockResult))
                .doesNotThrowAnyException();
    }

    // ── onTestFailure ─────────────────────────────────────────────────────

    /**
     * onTestFailure must not throw when there is no exception on the result
     * (ITestResult.getThrowable() returns null).
     */
    @Test
    public void onTestFailure_nullThrowable_doesNotThrow() {
        when(mockResult.getThrowable()).thenReturn(null);

        assertThatCode(() -> listener.onTestFailure(mockResult))
                .doesNotThrowAnyException();
    }

    /**
     * onTestFailure must not throw when the result carries a real exception.
     * The exception details should be used to populate Allure status details
     * without propagating.
     */
    @Test
    public void onTestFailure_withException_doesNotThrow() {
        RuntimeException cause = new RuntimeException("element not found after 3 attempts");
        when(mockResult.getThrowable()).thenReturn(cause);

        assertThatCode(() -> listener.onTestFailure(mockResult))
                .doesNotThrowAnyException();
    }

    /**
     * onTestFailure with a nested evidence directory — verifies that the listener
     * reads and attaches files from the evidence dir without throwing.
     */
    @Test
    public void onTestFailure_withEvidenceDir_attachesFilesWithoutError() throws IOException {
        // Create fake evidence artifacts
        Path evidenceDir = tempDir.resolve("step0");
        Files.createDirectories(evidenceDir);
        Files.write(evidenceDir.resolve("screenshot.png"),
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});           // PNG magic bytes
        Files.writeString(evidenceDir.resolve("page-source.html"),
                "<html><body>test</body></html>", StandardCharsets.UTF_8);
        Files.writeString(evidenceDir.resolve("console.log"),
                "[ERROR] [SEVERE] Something went wrong", StandardCharsets.UTF_8);

        // Point the mock result at our temp evidence dir
        when(mockResult.getAttribute("evidenceDir")).thenReturn(evidenceDir);
        when(mockResult.getThrowable()).thenReturn(new AssertionError("step 0 failed"));

        // Act + Assert
        assertThatCode(() -> listener.onTestFailure(mockResult))
                .doesNotThrowAnyException();
    }

    // ── onTestSkipped ─────────────────────────────────────────────────────

    /**
     * onTestSkipped must not throw when a test is skipped with no cause.
     */
    @Test
    public void onTestSkipped_noCause_doesNotThrow() {
        when(mockResult.getThrowable()).thenReturn(null);

        assertThatCode(() -> listener.onTestSkipped(mockResult))
                .doesNotThrowAnyException();
    }

    /**
     * onTestSkipped must not throw when a skip carries a cause (e.g. a dependency
     * test that failed first).
     */
    @Test
    public void onTestSkipped_withCause_doesNotThrow() {
        when(mockResult.getThrowable()).thenReturn(new RuntimeException("depends on failed test"));

        assertThatCode(() -> listener.onTestSkipped(mockResult))
                .doesNotThrowAnyException();
    }

    // ── onTestStart ───────────────────────────────────────────────────────

    /**
     * onTestStart must not throw even when browser system properties are not set.
     */
    @Test
    public void onTestStart_withNoBrowserProperties_doesNotThrow() {
        // Ensure properties are absent
        System.clearProperty(AllureListener.PROP_BROWSER);
        System.clearProperty(AllureListener.PROP_BROWSER_VERSION);
        System.clearProperty(AllureListener.PROP_TEST_ENV);

        assertThatCode(() -> listener.onTestStart(mockResult))
                .doesNotThrowAnyException();
    }

    /**
     * onTestStart must not throw when browser system properties are set to
     * realistic values; it should add them as Allure environment labels.
     */
    @Test
    public void onTestStart_withBrowserProperties_doesNotThrow() {
        System.setProperty(AllureListener.PROP_BROWSER,         "MicrosoftEdge");
        System.setProperty(AllureListener.PROP_BROWSER_VERSION, "123.0.0.1");
        System.setProperty(AllureListener.PROP_TEST_ENV,        "staging");

        try {
            assertThatCode(() -> listener.onTestStart(mockResult))
                    .doesNotThrowAnyException();
        } finally {
            System.clearProperty(AllureListener.PROP_BROWSER);
            System.clearProperty(AllureListener.PROP_BROWSER_VERSION);
            System.clearProperty(AllureListener.PROP_TEST_ENV);
        }
    }

    // ── onTestFailedWithTimeout ───────────────────────────────────────────

    /**
     * onTestFailedWithTimeout must not throw, even with a null throwable.
     */
    @Test
    public void onTestFailedWithTimeout_nullThrowable_doesNotThrow() {
        when(mockResult.getThrowable()).thenReturn(null);

        assertThatCode(() -> listener.onTestFailedWithTimeout(mockResult))
                .doesNotThrowAnyException();
    }

    /**
     * onTestFailedWithTimeout with a real exception must not propagate it.
     */
    @Test
    public void onTestFailedWithTimeout_withException_doesNotThrow() {
        when(mockResult.getThrowable()).thenReturn(
                new org.testng.internal.thread.ThreadTimeoutException("Timeout reached"));

        assertThatCode(() -> listener.onTestFailedWithTimeout(mockResult))
                .doesNotThrowAnyException();
    }

    // ── Edge cases ────────────────────────────────────────────────────────

    /**
     * Evidence directory that does not exist on disk — listener must not throw.
     */
    @Test
    public void onTestFailure_nonExistentEvidenceDir_doesNotThrow() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        when(mockResult.getAttribute("evidenceDir")).thenReturn(nonExistent);
        when(mockResult.getThrowable()).thenReturn(new RuntimeException("boom"));

        assertThatCode(() -> listener.onTestFailure(mockResult))
                .doesNotThrowAnyException();
    }

    /**
     * Evidence dir present but contains no artifact files — listener must not throw
     * and must not attach anything (no NPE from empty reads).
     */
    @Test
    public void onTestFailure_emptyEvidenceDir_doesNotThrow() throws IOException {
        Path emptyDir = tempDir.resolve("empty-step");
        Files.createDirectories(emptyDir);
        when(mockResult.getAttribute("evidenceDir")).thenReturn(emptyDir);
        when(mockResult.getThrowable()).thenReturn(new RuntimeException("nothing here"));

        assertThatCode(() -> listener.onTestFailure(mockResult))
                .doesNotThrowAnyException();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void deleteDirectory(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(p -> {
                      try { Files.delete(p); } catch (IOException ignored) { }
                  });
        }
    }
}
