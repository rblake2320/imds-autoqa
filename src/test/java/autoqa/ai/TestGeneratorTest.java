package autoqa.ai;

import autoqa.model.ElementInfo;
import autoqa.model.RecordedEvent;
import autoqa.model.RecordedSession;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TestGenerator}.
 *
 * <p>All LLM calls are intercepted via Mockito — no real HTTP traffic is made.
 * A fresh temporary directory is created for each test and deleted in teardown.
 */
public class TestGeneratorTest {

    private Path tempDir;

    @BeforeMethod
    public void setup() throws IOException {
        tempDir = Files.createTempDirectory("autoqa-testgen-");
    }

    @AfterMethod
    public void cleanup() throws IOException {
        if (Files.exists(tempDir)) {
            Files.walk(tempDir)
                 .sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
        }
    }

    // ── generate() ────────────────────────────────────────────────────────

    @Test
    public void generate_validSession_writesJavaFile() throws IOException {
        LLMClient mockLlm = mock(LLMClient.class);
        when(mockLlm.complete(any())).thenReturn("""
                ```java
                package generated;
                import org.testng.annotations.Test;
                public class AutoQATest_abc12345 {
                    @Test public void testRecordedFlow() { }
                }
                ```
                """);

        TestGenerator gen    = new TestGenerator(mockLlm, tempDir);
        Path          output = gen.generate(buildSession());

        assertThat(output).exists();
        assertThat(output.toString()).endsWith(".java");

        String content = Files.readString(output);
        assertThat(content).contains("package generated");
        assertThat(content).contains("@Test");
    }

    @Test
    public void generate_outputFileIsInsideOutputDir() throws IOException {
        LLMClient mockLlm = mock(LLMClient.class);
        when(mockLlm.complete(any())).thenReturn("```java\npublic class T {}\n```");

        TestGenerator gen    = new TestGenerator(mockLlm, tempDir);
        Path          output = gen.generate(buildSession());

        assertThat(output.toAbsolutePath())
                .startsWith(tempDir.toAbsolutePath());
    }

    @Test
    public void generate_outputDirCreatedIfMissing() throws IOException {
        Path nonExistentDir = tempDir.resolve("nested").resolve("output");
        assertThat(nonExistentDir).doesNotExist();

        LLMClient mockLlm = mock(LLMClient.class);
        when(mockLlm.complete(any())).thenReturn("```java\npublic class T {}\n```");

        TestGenerator gen = new TestGenerator(mockLlm, nonExistentDir);
        gen.generate(buildSession());

        assertThat(nonExistentDir).isDirectory();
    }

    @Test
    public void generate_llmThrows_propagatesException() throws IOException {
        LLMClient mockLlm = mock(LLMClient.class);
        when(mockLlm.complete(any())).thenThrow(new IOException("LLM offline"));

        TestGenerator gen = new TestGenerator(mockLlm, tempDir);

        assertThatThrownBy(() -> gen.generate(buildSession()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("LLM offline");
    }

    @Test
    public void generate_promptContainsStepDetails() throws IOException {
        LLMClient mockLlm = mock(LLMClient.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LLMClient.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        when(mockLlm.complete(captor.capture()))
                .thenReturn("```java\npublic class T {}\n```");

        TestGenerator gen = new TestGenerator(mockLlm, tempDir);
        gen.generate(buildSession());

        // The captured list has [system, user]; we verify the user message.
        List<LLMClient.ChatMessage> messages = captor.getValue();
        assertThat(messages).hasSize(2);

        String userMsg = messages.get(1).content();
        assertThat(userMsg).contains("NAVIGATE");
        assertThat(userMsg).contains("CLICK");
    }

    @Test
    public void generate_promptContainsSessionId() throws IOException {
        LLMClient mockLlm = mock(LLMClient.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LLMClient.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        when(mockLlm.complete(captor.capture()))
                .thenReturn("```java\npublic class T {}\n```");

        TestGenerator gen     = new TestGenerator(mockLlm, tempDir);
        RecordedSession session = buildSession();
        gen.generate(session);

        String userMsg = captor.getValue().get(1).content();
        assertThat(userMsg).contains(session.getSessionId());
    }

    @Test
    public void generate_systemPromptMentionsTestNG() throws IOException {
        LLMClient mockLlm = mock(LLMClient.class);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LLMClient.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        when(mockLlm.complete(captor.capture()))
                .thenReturn("```java\npublic class T {}\n```");

        new TestGenerator(mockLlm, tempDir).generate(buildSession());

        String systemMsg = captor.getValue().get(0).content();
        assertThat(systemMsg).containsIgnoringCase("TestNG");
        assertThat(systemMsg).containsIgnoringCase("@BeforeMethod");
        assertThat(systemMsg).containsIgnoringCase("@AfterMethod");
    }

    // ── extractJavaCode() ─────────────────────────────────────────────────

    @Test
    public void extractJavaCode_withFences_extractsInner() {
        TestGenerator gen      = new TestGenerator(null, tempDir);
        String        response = "Here is the code:\n```java\npublic class Foo {}\n```\nDone.";
        assertThat(gen.extractJavaCode(response)).isEqualTo("public class Foo {}");
    }

    @Test
    public void extractJavaCode_withoutFences_returnsFullResponse() {
        TestGenerator gen  = new TestGenerator(null, tempDir);
        String        code = "public class Foo {}";
        assertThat(gen.extractJavaCode(code)).isEqualTo(code);
    }

    @Test
    public void extractJavaCode_withUnlabelledFences_extractsInner() {
        TestGenerator gen      = new TestGenerator(null, tempDir);
        // Triple-backtick block with no language label
        String        response = "```\npublic class Bar {}\n```";
        assertThat(gen.extractJavaCode(response)).isEqualTo("public class Bar {}");
    }

    @Test
    public void extractJavaCode_stripsLeadingAndTrailingWhitespace() {
        TestGenerator gen      = new TestGenerator(null, tempDir);
        String        response = "```java\n  public class Baz {}  \n```";
        // The group itself is stripped via .strip()
        assertThat(gen.extractJavaCode(response)).isEqualTo("public class Baz {}");
    }

    @Test
    public void extractJavaCode_multilineClass_preservesInternalNewlines() {
        TestGenerator gen = new TestGenerator(null, tempDir);
        String inner = "public class Multi {\n    void m() {}\n}";
        String response = "```java\n" + inner + "\n```";
        assertThat(gen.extractJavaCode(response)).isEqualTo(inner);
    }

    @Test
    public void extractJavaCode_emptyFencedBlock_returnsEmptyString() {
        TestGenerator gen      = new TestGenerator(null, tempDir);
        String        response = "```java\n\n```";
        // The group "\n" strips to ""
        assertThat(gen.extractJavaCode(response)).isEmpty();
    }

    // ── deriveClassName() ─────────────────────────────────────────────────

    @Test
    public void deriveClassName_validSessionId_sanitizesCorrectly() {
        TestGenerator   gen     = new TestGenerator(null, tempDir);
        RecordedSession session = new RecordedSession();
        session.setSessionId("abc12345-def6-7890-abcd-ef1234567890");

        String className = gen.deriveClassName(session);

        assertThat(className).startsWith("AutoQATest_");
        assertThat(className).doesNotContain("-");
    }

    @Test
    public void deriveClassName_usesFirst8CharsOfSanitisedId() {
        TestGenerator   gen     = new TestGenerator(null, tempDir);
        RecordedSession session = new RecordedSession();
        // After removing hyphens: "abc12345def6..."
        session.setSessionId("abc12345-def6-7890-abcd-ef1234567890");

        String className = gen.deriveClassName(session);

        // Prefix "AutoQATest_" + first 8 chars of "abc12345def6..." = "abc12345"
        assertThat(className).isEqualTo("AutoQATest_abc12345");
    }

    @Test
    public void deriveClassName_shortSessionId_usesFullSanitisedId() {
        TestGenerator   gen     = new TestGenerator(null, tempDir);
        RecordedSession session = new RecordedSession();
        session.setSessionId("abc");

        String className = gen.deriveClassName(session);

        assertThat(className).isEqualTo("AutoQATest_abc");
    }

    @Test
    public void deriveClassName_nullSessionId_fallsBackToTimestamp() {
        TestGenerator   gen     = new TestGenerator(null, tempDir);
        RecordedSession session = new RecordedSession();
        // sessionId left null

        String className = gen.deriveClassName(session);

        assertThat(className).startsWith("AutoQATest_");
        // Timestamp suffix is at least 15 chars (yyyyMMdd_HHmmss)
        assertThat(className.length()).isGreaterThanOrEqualTo("AutoQATest_".length() + 15);
    }

    @Test
    public void deriveClassName_blankSessionId_fallsBackToTimestamp() {
        TestGenerator   gen     = new TestGenerator(null, tempDir);
        RecordedSession session = new RecordedSession();
        session.setSessionId("   ");

        String className = gen.deriveClassName(session);

        assertThat(className).startsWith("AutoQATest_");
    }

    @Test
    public void deriveClassName_sessionIdWithHyphensOnly_fallsBackOrEmpty() {
        TestGenerator   gen     = new TestGenerator(null, tempDir);
        RecordedSession session = new RecordedSession();
        // After stripping hyphens the string is empty — treated as blank → timestamp fallback
        session.setSessionId("----");

        String className = gen.deriveClassName(session);

        assertThat(className).startsWith("AutoQATest_");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Builds a minimal two-step {@link RecordedSession} (NAVIGATE then CLICK)
     * for use across multiple tests.
     */
    private RecordedSession buildSession() {
        RecordedSession session = new RecordedSession();
        session.setSessionId("abc12345-1234-1234-1234-123456789012");
        session.setStartTimestamp(Instant.now());
        session.setBrowserName("Microsoft Edge");

        RecordedEvent nav = new RecordedEvent();
        nav.setEventType(RecordedEvent.EventType.NAVIGATE);
        nav.setTimestamp(Instant.now());
        nav.setUrl("https://example.com");
        session.addEvent(nav);

        RecordedEvent click = new RecordedEvent();
        click.setEventType(RecordedEvent.EventType.CLICK);
        click.setTimestamp(Instant.now());
        ElementInfo el = new ElementInfo();
        el.setTagName("button");
        el.setId("submit");
        click.setElement(el);
        session.addEvent(click);

        return session;
    }
}
