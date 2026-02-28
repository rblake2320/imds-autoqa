package autoqa.cli;

import autoqa.ai.AIConfig;
import autoqa.ai.LocatorHealer;
import autoqa.ai.TestGenerator;
import autoqa.keyword.KeywordAction;
import autoqa.keyword.KeywordEngine;
import autoqa.model.ElementInfo;
import autoqa.model.ElementLocator;
import autoqa.model.ObjectRepository;
import autoqa.model.RecordedSession;
import autoqa.model.RecordingIO;
import autoqa.model.TestObject;
import autoqa.player.PlayerEngine;
import autoqa.recorder.RecorderCLI;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
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
                WrapperCLI.OrCommand.class,
                WrapperCLI.KeywordCommand.class,
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

        @Option(
                names       = {"-b", "--browser"},
                description = "Browser to use: edge, chrome, firefox (default: edge)",
                defaultValue = "edge"
        )
        String browser;

        @Option(
                names       = {"--or-file"},
                description = "Path to object-repository.json (optional — enables OR name resolution)"
        )
        Path orFile;

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

            System.out.printf("Starting %s WebDriver (Selenium Manager auto-downloads driver)...%n",
                    browser.toLowerCase());
            WebDriver driver = createDriver(browser);
            PlayerEngine engine = new PlayerEngine(driver);

            // Attach OR if supplied
            if (orFile != null) {
                if (!Files.exists(orFile)) {
                    System.err.println("OR file not found: " + orFile.toAbsolutePath());
                    return 1;
                }
                ObjectRepository or = ObjectRepository.load(orFile);
                engine.setObjectRepository(or);
                System.out.printf("  OR loaded : %d objects from %s%n", or.size(), orFile.getFileName());
            }

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

        /**
         * Creates a WebDriver for the requested browser.
         * Selenium Manager (built into Selenium 4.11+) automatically downloads
         * the matching browser driver — no manual chromedriver/geckodriver needed.
         */
        private WebDriver createDriver(String browser) {
            return switch (browser.toLowerCase().trim()) {
                case "chrome" -> {
                    ChromeOptions opts = new ChromeOptions();
                    opts.addArguments("--start-maximized");
                    yield new ChromeDriver(opts);
                }
                case "firefox" -> {
                    FirefoxOptions opts = new FirefoxOptions();
                    yield new FirefoxDriver(opts);
                }
                default -> {
                    EdgeOptions opts = new EdgeOptions();
                    opts.addArguments("--start-maximized");
                    yield new EdgeDriver(opts);
                }
            };
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

    // ── Object Repository command group ──────────────────────────────────────

    /**
     * Object Repository management — the IMDS AutoQA equivalent of UFT One's
     * shared (.tsr) OR operations: list, add, import, diff, merge.
     *
     * <p>Usage examples:
     * <pre>
     *   autoqa or list  my.json
     *   autoqa or add   my.json --name loginButton --class button --id login-btn
     *   autoqa or import recording.json my.json --name loginButton --class button
     *   autoqa or diff  a.json b.json
     *   autoqa or merge target.json source.json
     * </pre>
     */
    @Command(
            name        = "or",
            description = "Manage the shared Object Repository",
            mixinStandardHelpOptions = true,
            subcommands = {
                    WrapperCLI.OrListCommand.class,
                    WrapperCLI.OrAddCommand.class,
                    WrapperCLI.OrImportCommand.class,
                    WrapperCLI.OrDiffCommand.class,
                    WrapperCLI.OrMergeCommand.class
            }
    )
    static class OrCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            CommandLine.usage(this, System.out);
            return 0;
        }
    }

    /** Lists all objects in an OR file. */
    @Command(name = "list", description = "List all objects in an Object Repository",
             mixinStandardHelpOptions = true)
    static class OrListCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to object-repository.json",
                    defaultValue = ObjectRepository.DEFAULT_FILENAME)
        Path orFile;

        @Override
        public Integer call() throws Exception {
            ObjectRepository or = loadOr(orFile);
            System.out.printf("Object Repository: %s (%d objects)%n%n",
                    orFile.toAbsolutePath(), or.size());
            for (TestObject obj : or.all()) {
                System.out.printf("  %-30s  class=%-12s  locators=%d%n",
                        obj.getName(), obj.getObjectClass(), obj.getLocators().size());
                for (ElementLocator loc : obj.getLocators()) {
                    System.out.printf("      %-6s  %s%n", loc.getStrategy(), loc.getValue());
                }
            }
            return 0;
        }
    }

    /** Adds or updates a named object in an OR file. */
    @Command(name = "add", description = "Add or update a named object in an Object Repository",
             mixinStandardHelpOptions = true)
    static class OrAddCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to object-repository.json",
                    defaultValue = ObjectRepository.DEFAULT_FILENAME)
        Path orFile;

        @Option(names = {"--name"}, required = true,  description = "Logical object name")
        String name;

        @Option(names = {"--class"}, description = "Object class (button, input, link, …)")
        String objectClass;

        @Option(names = {"--id"},    description = "ID locator value")
        String id;

        @Option(names = {"--css"},   description = "CSS selector locator value")
        String css;

        @Option(names = {"--xpath"}, description = "XPath locator value")
        String xpath;

        @Option(names = {"--name-attr"}, description = "HTML name attribute locator value")
        String nameAttr;

        @Override
        public Integer call() throws Exception {
            ObjectRepository or = Files.exists(orFile) ? ObjectRepository.load(orFile) : ObjectRepository.empty();

            TestObject obj = new TestObject(name, objectClass != null ? objectClass : "element");
            if (id       != null) obj.addLocator(ElementLocator.Strategy.ID,    id);
            if (nameAttr != null) obj.addLocator(ElementLocator.Strategy.NAME,  nameAttr);
            if (css      != null) obj.addLocator(ElementLocator.Strategy.CSS,   css);
            if (xpath    != null) obj.addLocator(ElementLocator.Strategy.XPATH, xpath);

            boolean existed = or.contains(name);
            or.add(obj);
            or.save(orFile);

            System.out.printf("%s '%s' in %s (%d locators)%n",
                    existed ? "Updated" : "Added", name, orFile.getFileName(), obj.getLocators().size());
            return 0;
        }
    }

    /** Imports an element from a recording into an OR as a named object. */
    @Command(name = "import",
             description = "Import an element from a recording into the Object Repository",
             mixinStandardHelpOptions = true)
    static class OrImportCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to recording JSON file")
        Path recordingFile;

        @Parameters(index = "1", description = "Path to object-repository.json",
                    defaultValue = ObjectRepository.DEFAULT_FILENAME)
        Path orFile;

        @Option(names = {"--name"},  required = true, description = "Logical object name to assign")
        String name;

        @Option(names = {"--class"}, description = "Object class (default: element)")
        String objectClass;

        @Option(names = {"--step"},  description = "Step index (0-based) to import from (default: 0)",
                defaultValue = "0")
        int stepIndex;

        @Override
        public Integer call() throws Exception {
            if (!Files.exists(recordingFile)) {
                System.err.println("Recording not found: " + recordingFile);
                return 1;
            }

            RecordedSession session = RecordingIO.read(recordingFile);
            if (stepIndex >= session.getEventCount()) {
                System.err.printf("Step %d out of range (0-%d)%n",
                        stepIndex, session.getEventCount() - 1);
                return 1;
            }

            var event = session.getEvents().get(stepIndex);
            if (!event.hasElement()) {
                System.err.printf("Step %d (%s) has no element info%n",
                        stepIndex, event.getEventType());
                return 1;
            }

            ObjectRepository or = Files.exists(orFile) ? ObjectRepository.load(orFile) : ObjectRepository.empty();
            TestObject obj = or.importFromElementInfo(
                    name,
                    objectClass != null ? objectClass : "element",
                    event.getElement(),
                    event.getUrl());
            or.save(orFile);

            System.out.printf("Imported '%s' from step %d into %s (%d locators)%n",
                    name, stepIndex, orFile.getFileName(), obj.getLocators().size());
            return 0;
        }
    }

    /** Diffs two OR files and shows objects unique to each. */
    @Command(name = "diff", description = "Compare two Object Repository files",
             mixinStandardHelpOptions = true)
    static class OrDiffCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "First OR file (A)")
        Path fileA;

        @Parameters(index = "1", description = "Second OR file (B)")
        Path fileB;

        @Override
        public Integer call() throws Exception {
            ObjectRepository a = loadOr(fileA);
            ObjectRepository b = loadOr(fileB);
            ObjectRepository.OrdiffResult diff = a.diff(b);

            System.out.printf("OR Diff: %s vs %s%n%n", fileA.getFileName(), fileB.getFileName());
            System.out.printf("Only in A (%d): %s%n", diff.onlyInA().size(), diff.onlyInA());
            System.out.printf("Only in B (%d): %s%n", diff.onlyInB().size(), diff.onlyInB());
            System.out.printf("Common   (%d): %s%n", diff.common().size(),   diff.common());
            return 0;
        }
    }

    /** Merges source OR into target OR (last-write wins, UFT parity). */
    @Command(name = "merge", description = "Merge source OR into target OR (last-write wins)",
             mixinStandardHelpOptions = true)
    static class OrMergeCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Target OR file (modified in place)")
        Path target;

        @Parameters(index = "1", description = "Source OR file (merged into target)")
        Path source;

        @Override
        public Integer call() throws Exception {
            ObjectRepository targetOr = Files.exists(target)
                    ? ObjectRepository.load(target) : ObjectRepository.empty();
            ObjectRepository sourceOr = loadOr(source);

            int added = targetOr.merge(sourceOr);
            targetOr.save(target);

            System.out.printf("Merged %d object(s) from %s into %s (total: %d)%n",
                    added, source.getFileName(), target.getFileName(), targetOr.size());
            return 0;
        }
    }

    /** Shared helper: loads an OR or fails with a clear message. */
    private static ObjectRepository loadOr(Path path) throws IOException {
        if (!Files.exists(path)) {
            System.err.println("OR file not found: " + path.toAbsolutePath());
            System.exit(1);
        }
        return ObjectRepository.load(path);
    }

    // ── Keyword-Driven Testing ────────────────────────────────────────────────

    /**
     * Runs a keyword-driven test file against the browser.
     *
     * <p>The keyword test file is a JSON array of steps, each with:
     * <pre>
     *   [{"keyword": "navigate", "target": "https://example.com"},
     *    {"keyword": "click",    "target": "#login-btn"},
     *    {"keyword": "verifyText","target": "#msg", "params": {"expected": "Welcome"}}]
     * </pre>
     *
     * <p>Usage:
     * <pre>
     *   autoqa keyword run my-test.json
     *   autoqa keyword run my-test.json --browser chrome --or-file objects.json
     * </pre>
     */
    @Command(
            name        = "keyword",
            description = "Keyword-driven testing commands",
            mixinStandardHelpOptions = true,
            subcommands = { WrapperCLI.KeywordRunCommand.class }
    )
    static class KeywordCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            CommandLine.usage(this, System.out);
            return 0;
        }
    }

    @Command(
            name        = "run",
            description = "Run a keyword-driven test JSON file",
            mixinStandardHelpOptions = true
    )
    static class KeywordRunCommand implements Callable<Integer> {

        private static final Logger log = LoggerFactory.getLogger(KeywordRunCommand.class);

        @Parameters(index = "0", description = "Path to keyword test JSON file")
        Path testFile;

        @Option(
                names       = {"-b", "--browser"},
                description = "Browser to use: edge, chrome, firefox (default: edge)",
                defaultValue = "edge"
        )
        String browser;

        @Option(
                names       = {"--or-file"},
                description = "Path to object-repository.json (optional)"
        )
        Path orFile;

        @Override
        public Integer call() throws Exception {
            if (!Files.exists(testFile)) {
                System.err.println("Keyword test file not found: " + testFile.toAbsolutePath());
                return 1;
            }

            System.out.println("Loading keyword test: " + testFile.toAbsolutePath());

            WebDriver driver = new PlayCommand().createDriver(browser);

            ObjectRepository or = null;
            if (orFile != null && Files.exists(orFile)) {
                or = ObjectRepository.load(orFile);
                System.out.printf("  OR loaded : %d objects%n", or.size());
            }

            KeywordEngine engine = or != null ? new KeywordEngine(driver, or) : new KeywordEngine(driver);
            KeywordEngine.RunResult result = engine.run(testFile);

            System.out.printf("%nKeyword test complete — %d/%d steps passed.%n",
                    result.getStepsCompleted(), result.getTotalSteps());

            if (!result.isSuccess()) {
                System.err.printf("FAILED: %s%n", result.getFailureReason());
                return 2;
            }
            return 0;
        }
    }

    // ── Version ───────────────────────────────────────────────────────────────

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
            System.out.println("Modules: recorder, player, ai, vision, data, api, keyword, desktop,");
            System.out.println("         accessibility, network, reporting, cli, gui");
            return 0;
        }
    }
}
