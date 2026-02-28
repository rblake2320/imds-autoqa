package autoqa.accessibility;

import org.testng.annotations.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AccessibilityReport} and {@link AccessibilityViolation}.
 * No live browser required.
 */
public class AccessibilityReportTest {

    private static AccessibilityViolation critical() {
        return new AccessibilityViolation("color-contrast", "Elements must have sufficient color contrast",
                "critical", "wcag2aa", List.of("#header", ".nav"));
    }

    private static AccessibilityViolation minor() {
        return new AccessibilityViolation("landmark-one-main", "Page should contain a level-one heading",
                "minor", "best-practice", List.of());
    }

    @Test
    public void emptyViolations_isPassed() {
        AccessibilityReport report = new AccessibilityReport(
                "https://example.com", "Test Page", List.of(), 10, 2);
        assertThat(report.isPassed()).isTrue();
        assertThat(report.getViolationCount()).isEqualTo(0);
    }

    @Test
    public void withViolations_isNotPassed() {
        AccessibilityReport report = new AccessibilityReport(
                "https://example.com", "Test Page", List.of(critical()), 5, 1);
        assertThat(report.isPassed()).isFalse();
        assertThat(report.getViolationCount()).isEqualTo(1);
    }

    @Test
    public void getCriticalViolations_returnsOnlySevere() {
        AccessibilityReport report = new AccessibilityReport(
                "https://example.com", "Test", List.of(critical(), minor()), 0, 0);
        assertThat(report.getCriticalViolations()).hasSize(1);
        assertThat(report.getCriticalViolations().get(0).getId()).isEqualTo("color-contrast");
    }

    @Test
    public void assertNone_passesWhenNoViolations() {
        AccessibilityReport report = new AccessibilityReport(
                "https://example.com", "Page", List.of(), 10, 0);
        report.assertNone(); // should not throw
    }

    @Test
    public void assertNone_throwsWhenViolationsExist() {
        AccessibilityReport report = new AccessibilityReport(
                "https://example.com", "Page", List.of(critical()), 5, 0);
        assertThatThrownBy(report::assertNone).isInstanceOf(AssertionError.class)
                .hasMessageContaining("color-contrast");
    }

    @Test
    public void assertNoCritical_passesWhenOnlyMinor() {
        AccessibilityReport report = new AccessibilityReport(
                "https://example.com", "Page", List.of(minor()), 5, 0);
        report.assertNoCritical(); // should not throw
    }

    @Test
    public void assertNoCritical_throwsWhenCriticalExists() {
        AccessibilityReport report = new AccessibilityReport(
                "https://example.com", "Page", List.of(critical()), 5, 0);
        assertThatThrownBy(report::assertNoCritical).isInstanceOf(AssertionError.class);
    }

    @Test
    public void assertMaxViolations_passesWhenUnderLimit() {
        AccessibilityReport report = new AccessibilityReport(
                "https://example.com", "Page", List.of(minor()), 5, 0);
        report.assertMaxViolations(5); // should not throw
    }

    @Test
    public void assertMaxViolations_throwsWhenOverLimit() {
        AccessibilityReport report = new AccessibilityReport(
                "https://example.com", "Page", List.of(critical(), minor()), 0, 0);
        assertThatThrownBy(() -> report.assertMaxViolations(1)).isInstanceOf(AssertionError.class);
    }

    @Test
    public void violation_isSevere_criticalReturnsTrue() {
        assertThat(critical().isSevere()).isTrue();
        assertThat(minor().isSevere()).isFalse();
    }

    @Test
    public void violation_toString_containsId() {
        assertThat(critical().toString()).contains("color-contrast");
    }

    @Test
    public void report_summary_containsUrl() {
        AccessibilityReport report = new AccessibilityReport(
                "https://example.com", "Page", List.of(), 5, 0);
        assertThat(report.summary()).contains("example.com");
    }
}
