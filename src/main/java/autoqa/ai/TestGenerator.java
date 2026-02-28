package autoqa.ai;

import autoqa.model.ElementInfo;
import autoqa.model.InputData;
import autoqa.model.RecordedEvent;
import autoqa.model.RecordedSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a Java TestNG test class from a RecordedSession.
 * Uses the LLM to produce readable, maintainable test code.
 * Writes output to the configured generated-tests/ directory.
 */
public class TestGenerator {

    private static final Logger log = LoggerFactory.getLogger(TestGenerator.class);

    /** Regex to extract Java code from triple-backtick fenced block. */
    private static final Pattern CODE_BLOCK = Pattern.compile(
            "```(?:java)?\\s*\\n([\\s\\S]*?)\\n```", Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter CLASS_TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final LLMClient llm;
    private final Path outputDir;

    public TestGenerator(LLMClient llm, Path outputDir) {
        this.llm       = llm;
        this.outputDir = outputDir;
    }

    /**
     * Generates a Java TestNG test file from the recording.
     *
     * @param session the recorded session to turn into a test
     * @return path to the generated .java file
     * @throws IOException if the LLM call fails or the file cannot be written
     */
    public Path generate(RecordedSession session) throws IOException {
        String systemPrompt = buildSystemPrompt();
        String userPrompt   = buildUserPrompt(session);

        log.debug("Sending test generation prompt for session: {} ({} events)",
                session.getSessionId(), session.getEventCount());

        String response = llm.complete(List.of(
                LLMClient.ChatMessage.system(systemPrompt),
                LLMClient.ChatMessage.user(userPrompt)
        ));

        String javaCode   = extractJavaCode(response);
        String className  = deriveClassName(session);
        Path   outputFile = outputDir.resolve(className + ".java");

        Files.createDirectories(outputDir);
        Files.writeString(outputFile, javaCode);
        log.info("Generated test: {}", outputFile);
        return outputFile;
    }

    // ── Prompt builders ───────────────────────────────────────────────────

    private String buildSystemPrompt() {
        return """
                You are an expert Selenium/TestNG test engineer. Generate production-quality Java 17 test code.

                Rules:
                - Use TestNG annotations: @Test, @BeforeMethod, @AfterMethod
                - Use WebDriverManager or Selenium Manager (Selenium 4.11+ auto-downloads driver)
                - Each recorded step becomes a @Step-annotated method call
                - Use explicit WebDriverWait, never implicit waits
                - Add meaningful assertions (verify navigation succeeds, element is visible, etc.)
                - Use Allure @Step annotations for each interaction step
                - Include @BeforeMethod that creates EdgeDriver and @AfterMethod that quits
                - Class must be in package: generated
                - Import: org.openqa.selenium.edge.EdgeDriver, org.openqa.selenium.support.ui.*
                - Wrap the test in try/catch blocks with screenshot on failure
                - Return ONLY the complete Java source file inside ```java ... ``` fences
                """;
    }

    private String buildUserPrompt(RecordedSession session) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("Generate a TestNG test class for this recorded session.\n\n");
        sb.append("Session ID: ").append(session.getSessionId()).append("\n");
        sb.append("Browser: ").append(session.getBrowserName()).append("\n");
        sb.append("Total steps: ").append(session.getEventCount()).append("\n\n");
        sb.append("Steps:\n");

        int i = 1;
        for (RecordedEvent event : session.getEvents()) {
            sb.append(i++).append(". [").append(event.getEventType()).append("] ");

            if (event.getUrl() != null) {
                sb.append("URL: ").append(event.getUrl()).append(" | ");
            }

            if (event.getElement() != null) {
                ElementInfo el = event.getElement();
                sb.append("Element: <").append(el.getTagName()).append(">");
                if (el.getId() != null) {
                    sb.append(" id='").append(el.getId()).append("'");
                }
                if (el.getText() != null) {
                    int limit = Math.min(50, el.getText().length());
                    sb.append(" text='").append(el.getText(), 0, limit).append("'");
                }
            }

            if (event.getInputData() != null) {
                InputData d = event.getInputData();
                if (d.getKeys() != null && !"[REDACTED]".equals(d.getKeys())) {
                    sb.append(" input='").append(d.getKeys()).append("'");
                } else if (d.getKeyCode() != null) {
                    sb.append(" key=").append(d.getKeyCode());
                } else if (d.getSelectedOption() != null && d.getSelectedOption().getText() != null) {
                    sb.append(" select='").append(d.getSelectedOption().getText()).append("'");
                }
            }

            if (event.getComment() != null) {
                sb.append(" // ").append(event.getComment());
            }

            sb.append("\n");
        }

        sb.append("\nGenerate a complete TestNG Java test class:");
        return sb.toString();
    }

    // ── Code extraction ───────────────────────────────────────────────────

    /**
     * Extracts Java code from the LLM response.
     * If the response contains a triple-backtick fenced block, the inner content
     * is returned. Otherwise the entire (trimmed) response is treated as code.
     *
     * @param response raw LLM response string
     * @return Java source code ready to write to disk
     */
    String extractJavaCode(String response) {
        Matcher m = CODE_BLOCK.matcher(response);
        if (m.find()) {
            return m.group(1).strip();
        }
        // No fences — assume entire response is code (LLM complied without fences).
        return response.strip();
    }

    // ── Class name derivation ─────────────────────────────────────────────

    /**
     * Derives a valid Java class name from the session.
     * Uses the first 8 characters of the sanitised session ID when available,
     * otherwise falls back to a timestamp suffix.
     *
     * @param session the recorded session
     * @return a valid Java identifier suitable for use as a class name
     */
    String deriveClassName(RecordedSession session) {
        if (session.getSessionId() != null && !session.getSessionId().isBlank()) {
            // Strip hyphens and take up to 8 chars to keep the name readable.
            String sanitised = session.getSessionId().replace("-", "");
            if (!sanitised.isBlank()) {
                String suffix = sanitised.substring(0, Math.min(8, sanitised.length()));
                return "AutoQATest_" + suffix;
            }
        }
        // Fallback: timestamp-based name, guaranteed unique per second.
        String timestamp = LocalDateTime.now().format(CLASS_TIMESTAMP_FMT);
        return "AutoQATest_" + timestamp;
    }
}
