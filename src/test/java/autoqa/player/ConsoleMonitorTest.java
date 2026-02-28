package autoqa.player;

import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ConsoleMonitor} (no browser required).
 */
public class ConsoleMonitorTest {

    /** Creates a no-CDP stub monitor for testing data model and assertion logic. */
    private ConsoleMonitor stubMonitor() {
        return new ConsoleMonitor(null) {
            @Override
            public ConsoleMonitor start() { return this; }
            @Override
            public ConsoleMonitor stop()  { return this; }
        };
    }

    @Test
    public void record_andRetrieve_error() {
        ConsoleMonitor monitor = stubMonitor();
        monitor.record(ConsoleMonitor.Level.ERROR, "TypeError: Cannot read property", "app.js", 42);
        assertThat(monitor.getErrors()).hasSize(1);
        assertThat(monitor.getErrors().get(0).text()).contains("TypeError");
    }

    @Test
    public void assertNoErrors_passesWithNoErrors() {
        ConsoleMonitor monitor = stubMonitor();
        monitor.record(ConsoleMonitor.Level.INFO, "Page loaded", "", 0);
        monitor.assertNoErrors(); // should not throw
    }

    @Test
    public void assertNoErrors_throwsWhenErrorRecorded() {
        ConsoleMonitor monitor = stubMonitor();
        monitor.record(ConsoleMonitor.Level.ERROR, "Uncaught ReferenceError", "app.js", 100);
        assertThatThrownBy(monitor::assertNoErrors)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Uncaught ReferenceError");
    }

    @Test
    public void assertContains_passesWhenMessageFound() {
        ConsoleMonitor monitor = stubMonitor();
        monitor.record(ConsoleMonitor.Level.LOG, "App initialized successfully", "", 0);
        monitor.assertContains("App initialized");
    }

    @Test
    public void assertContains_throwsWhenNotFound() {
        ConsoleMonitor monitor = stubMonitor();
        monitor.record(ConsoleMonitor.Level.LOG, "Something else", "", 0);
        assertThatThrownBy(() -> monitor.assertContains("Missing message"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    public void clear_removesAllMessages() {
        ConsoleMonitor monitor = stubMonitor();
        monitor.record(ConsoleMonitor.Level.ERROR, "Error 1", "", 0);
        monitor.record(ConsoleMonitor.Level.WARNING, "Warning 1", "", 0);
        monitor.clear();
        assertThat(monitor.messages()).isEmpty();
    }

    @Test
    public void atLevel_filtersCorrectly() {
        ConsoleMonitor monitor = stubMonitor();
        monitor.record(ConsoleMonitor.Level.LOG,     "log message", "", 0);
        monitor.record(ConsoleMonitor.Level.WARNING, "warn message", "", 0);
        monitor.record(ConsoleMonitor.Level.ERROR,   "error message", "", 0);

        assertThat(monitor.atLevel(ConsoleMonitor.Level.WARNING)).hasSize(2); // WARNING + ERROR
        assertThat(monitor.atLevel(ConsoleMonitor.Level.ERROR)).hasSize(1);
    }

    @Test
    public void summary_containsCountInfo() {
        ConsoleMonitor monitor = stubMonitor();
        monitor.record(ConsoleMonitor.Level.ERROR, "err", "", 0);
        String summary = monitor.summary();
        assertThat(summary).contains("ConsoleMonitor");
        assertThat(summary).contains("errors=1");
    }

    @Test
    public void level_fromString_handlesAllCases() {
        assertThat(ConsoleMonitor.Level.fromString("error")).isEqualTo(ConsoleMonitor.Level.ERROR);
        assertThat(ConsoleMonitor.Level.fromString("warning")).isEqualTo(ConsoleMonitor.Level.WARNING);
        assertThat(ConsoleMonitor.Level.fromString("warn")).isEqualTo(ConsoleMonitor.Level.WARNING);
        assertThat(ConsoleMonitor.Level.fromString("info")).isEqualTo(ConsoleMonitor.Level.INFO);
        assertThat(ConsoleMonitor.Level.fromString(null)).isEqualTo(ConsoleMonitor.Level.LOG);
    }
}
