package autoqa.cli;

import autoqa.ai.AIConfig;
import autoqa.ai.LocatorHealer;
import autoqa.ai.TestGenerator;
import autoqa.model.ElementInfo;
import autoqa.model.RecordedSession;
import autoqa.model.RecordingIO;
import autoqa.player.PlayerEngine;
import autoqa.recorder.RecorderCLI;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Unified CLI entry-point for IMDS AutoQA.
 *
 * <p>Sub-commands:
 * <ul>
 *   <li>{@code autoqa record}    — record user interactions in Edge (delegates to RecorderCLI)</li>
 *   <li>{@code autoqa play}      — replay a saved recording</li>
 *   <li>{@code autoqa generate}  — generate Java TestNG test from recording via local LLM</li>
 *   <li>{@code autoqa run}       — run a TestNG suite via Maven Surefire</li>
 *   <li>{@code autoqa heal}      — demonstrate LLM locator healing on a recording</li>
 *   <li>{@code autoqa report}    — generate Allure report from existing test results</li>
 *   <li>{@code autoqa version}   — print build version</li>
 * </ul>
 *
 * <p>Main class wired into the fat-JAR manifest by maven-shade-plugin.
 */
@Command(
        name        = "autoqa",
        description = "IMDS AutoQA — air-gapped Selenium recorder/player with local LLM self-healing",
        version     = "1.0.0-SNAPSHOT",
        mixinStandardHelpOptions = true,
        subcommands = {
                RecorderCLI.class,
                WrapperCLI.PlayCommand.class,
                WrapperCLI.GenerateCommand.class,
                WrapperCLI.RunCommand.class,
                WrapperCLI.HealCommand.class,
                WrapperCLI.ReportCommand.class,
                WrapperCLI.VersionCommand.class
        }
)
public class WrapperCLI implements Callable<Integer> {

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    // ── Entry-point ─────────────────────────────────────────────────────────

    public static void main(String[] args) {
        if (args.length == 0) {
            // No arguments → open the desktop GUI (double-click or autoqa.bat)
            autoqa.gui.WrapperGUI.launch();
            return;
        }
        int exit = new CommandLine(new WrapperCLI()).execute(args);
        System.exit(exit);
    }

    // ── Sub-commands ─────────────────────────────────────────────────────────

    /**
     * Replays a saved recording against the currently open Edge browser.
     */
    @Command(
            name        = "play",
            description = "Replay a saved recording in Edge",
            mixinStandardHelpOptions = true
    )
    static class PlayCommand implements Callable<Integer> {

        private static final Logger log = LoggerFactory.getLogger(PlayCommand.class);

        @Parameters(index = "0", description = "Path to recording JSON file")
        Path recordingFile;

        @Option(
                names       = {"-e", "--evidence-dir"},
                description = "Directory for evidence on failure (default: evidence)",
                defaultValue = "evidence"
        )
        String evidenceDir;

        @Override
        public Integer call() throws Exception {
            if (!Files.exists(recordingFile)) {
                System.err.println("Recording file not found: " + recordingFile.toAbsolutePath());
                return 1;
            }

            System.out.println("Loading recording: " + recordingFile.toAbsolutePath());
            RecordedSession session = RecordingIO.read(recordingFile);
            System.out.printf("  Session   : %s%n", session.getSessionId());
            System.out.printf("  Events    : %d%n", session.getEventCount());
            System.out.printf("  Evidence  : %s%n", Path.of(evidenceDir).toAbsolutePath());

            System.out.println("Starting Edge WebDriver...");
            EdgeOptions opts = new EdgeOptions();
            WebDriver driver = new EdgeDriver(opts);
            PlayerEngine engine = new PlayerEngine(driver);
            PlayerEngine.PlaybackResult result = engine.play(session);

            System.out.printf("%nPlayback complete — %d/%d steps succeeded.%n",
                    result.getStepsCompleted(), result.getTotalSteps());

            if (!result.isSuccess()) {
                System.err.printf("Playback FAILED at step %d: %s%n",
                        result.getStepsCompleted(), result.getFailureReason());
                return 2;
            }
            return 0;
        }
    }

    /**
     * Generates a Java TestNG test file from a recording using the local LLM.
     */
    @Command(
            name        = "generate",
            description = "Generate a Java TestNG test from a recording using local LLM",
            mixinStandardHelpOptions = true
    )
    static class GenerateCommand implements Callable<Integer> {

        private static final Logger log = LoggerFactory.getLogger(GenerateCommand.class);

        @Parameters(index = "0", description = "Path to recording JSON file")
        Path recordingFile;

        @Option(
                names       = {"-o", "--output-dir"},
                description = "Directory for generated tests (default: generated-tests)",
                defaultValue = "generated-tests"
        )
        String outputDir;

        @Override
        public Integer call() throws Exception {
            if (!Files.exists(recordingFile)) {
                System.err.println("Recording file not found: " + recordingFile.toAbsolutePath());
                return 1;
            }

            System.out.println("Loading recording: " + recordingFile.toAbsolutePath());
            RecordedSession session = RecordingIO.read(recordingFile);
            System.out.printf("  Session   : %s (%d events)%n",
                    session.getSessionId(), session.getEventCount());

            AIConfig aiConfig = new AIConfig();
            TestGenerator generator = aiConfig.createTestGenerator();

            System.out.println("Calling local LLM to generate test (this may take 30-60 seconds)...");
            Path generated = generator.generate(session);

            System.out.println("Test generated: " + generated.toAbsolutePath());
            System.out.println("Add the generated file to your Maven source root and run: autoqa run");
            return 0;
        }
    }

    /**
     * Runs the TestNG regression suite via Maven Surefire.
     */
    @Command(
            name        = "run",
            description = "Run the TestNG regression suite and generate Allure report",
            mixinStandardHelpOptions = true
    )
    static class RunCommand implements Callable<Integer> {

        private static final Logger log = LoggerFactory.getLogger(RunCommand.class);

        @Option(
                names       = {"-s", "--suite"},
                description = "TestNG suite XML file (default: regression.xml)",
                defaultValue = "regression.xml"
        )
        String suiteFile;

        @Option(
                names       = {"--mvn"},
                description = "Path to mvn executable (default: mvn)",
                defaultValue = "mvn"
        )
        String mvnExe;

        @Override
        public Integer call() throws Exception {
            System.out.printf("Running TestNG suite: %s%n", suiteFile);

            List<String> cmd = new ArrayList<>(List.of(
                    mvnExe, "test",
                    "-Dsurefire.suiteXmlFiles=" + suiteFile
            ));

            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .inheritIO()
                    .directory(Path.of(".").toRealPath().toFile());

            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode == 0) {
                System.out.println("\nAll tests passed. Run 'allure serve target/allure-results' to view report.");
            } else {
                System.err.println("\nTests failed. Check target/allure-results for details.");
            }
            return exitCode == 0 ? 0 : 2;
        }
    }

    /**
     * Demonstrates LLM locator healing: loads a recording, intentionally corrupts
     * one locator, then shows the healed result without actually replaying.
     */
    @Command(
            name        = "heal",
            description = "Demonstrate LLM locator healing on a recording",
            mixinStandardHelpOptions = true
    )
    static class HealCommand implements Callable<Integer> {

        private static final Logger log = LoggerFactory.getLogger(HealCommand.class);

        @Parameters(index = "0", description = "Path to recording JSON file")
        Path recordingFile;

        @Option(
                names       = {"-s", "--step"},
                description = "Step index to heal (0-based, default: 0)",
                defaultValue = "0"
        )
        int stepIndex;

        @Override
        public Integer call() throws Exception {
            if (!Files.exists(recordingFile)) {
                System.err.println("Recording file not found: " + recordingFile.toAbsolutePath());
                return 1;
            }

            RecordedSession session = RecordingIO.read(recordingFile);
            if (session.getEventCount() == 0) {
                System.err.println("Recording has no events.");
                return 1;
            }
            if (stepIndex >= session.getEventCount()) {
                System.err.printf("Step index %d out of range (0-%d).%n",
                        stepIndex, session.getEventCount() - 1);
                return 1;
            }

            var event = session.getEvents().get(stepIndex);
            System.out.printf("Healing step %d: %s on %s%n",
                    stepIndex, event.getEventType(), event.getUrl());

            AIConfig aiConfig = new AIConfig();
            LocatorHealer healer = aiConfig.createLocatorHealer();

            // Build a synthetic ElementInfo with a broken XPath to demonstrate healing.
            ElementInfo brokenElement = new ElementInfo();
            brokenElement.setXpath("//button[@id='old-broken-id']");
            brokenElement.setText("Submit");

            String domSnippet = "<button id='submit-btn' class='submit-btn'>Submit</button>";
            LocatorHealer.HealingResult result = healer.heal(brokenElement, domSnippet, event.getUrl());

            System.out.printf("Original  : //button[@id='old-broken-id']%n");
            if (result.healed()) {
                System.out.printf("Healed    : %s%n", result.locatorValue());
                System.out.printf("Strategy  : %s%n", result.strategy());
            } else {
                System.out.printf("Healing   : FAILED — %s%n", result.failureReason());
            }
            return 0;
        }
    }

    /**
     * Generates an Allure HTML report from existing test results.
     */
    @Command(
            name        = "report",
            description = "Generate Allure HTML report from target/allure-results",
            mixinStandardHelpOptions = true
    )
    static class ReportCommand implements Callable<Integer> {

        private static final Logger log = LoggerFactory.getLogger(ReportCommand.class);

        @Option(
                names       = {"-r", "--results-dir"},
                description = "Allure results directory (default: target/allure-results)",
                defaultValue = "target/allure-results"
        )
        String resultsDir;

        @Option(
                names       = {"-o", "--output-dir"},
                description = "Report output directory (default: target/allure-report)",
                defaultValue = "target/allure-report"
        )
        String outputDir;

        @Option(
                names       = {"--serve"},
                description = "Open the report in a browser after generation",
                defaultValue = "false"
        )
        boolean serve;

        @Override
        public Integer call() throws Exception {
            Path results = Path.of(resultsDir);
            if (!Files.exists(results)) {
                System.err.println("Allure results not found: " + results.toAbsolutePath());
                System.err.println("Run 'autoqa run' first to generate test results.");
                return 1;
            }

            String command = serve ? "serve" : "generate";
            List<String> cmd = serve
                    ? List.of("allure", "serve", resultsDir)
                    : List.of("allure", "generate", resultsDir, "-o", outputDir, "--clean");

            System.out.printf("Running: allure %s %s%n", command, resultsDir);

            try {
                ProcessBuilder pb = new ProcessBuilder(cmd)
                        .inheritIO()
                        .directory(Path.of(".").toRealPath().toFile());
                Process process = pb.start();
                int exitCode = process.waitFor();

                if (!serve && exitCode == 0) {
                    System.out.println("Report generated: " + Path.of(outputDir).toAbsolutePath());
                    System.out.println("Open: " + Path.of(outputDir, "index.html").toAbsolutePath());
                }
                return exitCode == 0 ? 0 : 1;

            } catch (IOException e) {
                // allure CLI not on PATH
                System.out.println("'allure' command not found on PATH.");
                System.out.println("Install Allure from https://allurereport.org/docs/install/ or run:");
                System.out.printf("  mvn io.qameta.allure:allure-maven:report -Dallure.results.directory=%s%n",
                        results.toAbsolutePath());
                return 0; // not fatal
            }
        }
    }

    /**
     * Prints the version string.
     */
    @Command(
            name        = "version",
            description = "Print IMDS AutoQA version",
            mixinStandardHelpOptions = true
    )
    static class VersionCommand implements Callable<Integer> {

        @Override
        public Integer call() {
            System.out.println("IMDS AutoQA 1.0.0-SNAPSHOT");
            System.out.println("Selenium WebDriver 4.19.1 | TestNG 7.10.1 | Allure 2.27.0");
            return 0;
        }
    }
}
