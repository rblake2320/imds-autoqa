package autoqa.ai;

import autoqa.model.ElementInfo;
import autoqa.model.ElementLocator;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LocatorHealer}.
 *
 * <p>The {@link LLMClient} is mocked with Mockito so that tests exercise the
 * prompt construction and response-parsing logic without requiring a running
 * Ollama instance.
 */
public class LocatorHealerTest {

    // ── CSS response path ─────────────────────────────────────────────────

    @Test
    public void heal_validCssResponse_returnsCssStrategy() throws IOException {
        LLMClient mockLlm = mock(LLMClient.class);
        when(mockLlm.complete(any())).thenReturn("#submit-button");

        LocatorHealer healer = new LocatorHealer(mockLlm, 1000);

        ElementInfo el = new ElementInfo();
        el.setTagName("button");
        el.setId("old-id");

        LocatorHealer.HealingResult result = healer.heal(
                el,
                "<html><body><button id='submit-button'>Submit</button></body></html>",
                "http://example.com");

        assertThat(result.healed()).isTrue();
        assertThat(result.locatorValue()).isEqualTo("#submit-button");
        assertThat(result.strategy()).isEqualTo(ElementLocator.Strategy.CSS);
        assertThat(result.failureReason()).isNull();
    }

    // ── XPath response path ───────────────────────────────────────────────

    @Test
    public void heal_xpathResponse_returnsXpathStrategy() throws IOException {
        LLMClient mockLlm = mock(LLMClient.class);
        when(mockLlm.complete(any())).thenReturn("//button[@data-testid='submit']");

        LocatorHealer healer = new LocatorHealer(mockLlm, 1000);

        LocatorHealer.HealingResult result = healer.heal(
                new ElementInfo(),
                "<html></html>",
                "http://test.com");

        assertThat(result.healed()).isTrue();
        assertThat(result.strategy()).isEqualTo(ElementLocator.Strategy.XPATH);
        assertThat(result.locatorValue()).isEqualTo("//button[@data-testid='submit']");
    }

    // ── XPath with grouping parens: (// ──────────────────────────────────

    @Test
    public void heal_groupedXpathResponse_returnsXpathStrategy() throws IOException {
        LLMClient mockLlm = mock(LLMClient.class);
        when(mockLlm.complete(any())).thenReturn("(//input[@type='submit'])[1]");

        LocatorHealer healer = new LocatorHealer(mockLlm, 1000);

        LocatorHealer.HealingResult result = healer.heal(
                new ElementInfo(), "<html></html>", "http://test.com");

        assertThat(result.healed()).isTrue();
        assertThat(result.strategy()).isEqualTo(ElementLocator.Strategy.XPATH);
    }

    // ── CANNOT_HEAL sentinel ──────────────────────────────────────────────

    @Test
    public void heal_cannotHealResponse_returnsFailure() throws IOException {
        LLMClient mockLlm = mock(LLMClient.class);
        when(mockLlm.complete(any())).thenReturn("CANNOT_HEAL");

        LocatorHealer healer = new LocatorHealer(mockLlm, 1000);

        LocatorHealer.HealingResult result = healer.heal(
                new ElementInfo(), "", "http://test.com");

        assertThat(result.healed()).isFalse();
        assertThat(result.locatorValue()).isNull();
        assertThat(result.strategy()).isNull();
    }

    // ── Blank response treated as CANNOT_HEAL ─────────────────────────────

    @Test
    public void heal_blankResponse_returnsFailure() throws IOException {
        LLMClient mockLlm = mock(LLMClient.class);
        when(mockLlm.complete(any())).thenReturn("   "); // blank after trim

        LocatorHealer healer = new LocatorHealer(mockLlm, 1000);

        LocatorHealer.HealingResult result = healer.heal(
                new ElementInfo(), "", "http://test.com");

        assertThat(result.healed()).isFalse();
    }

    // ── LLM throws IOException ────────────────────────────────────────────

    @Test
    public void heal_llmException_returnsFailure() throws IOException {
        LLMClient mockLlm = mock(LLMClient.class);
        when(mockLlm.complete(any())).thenThrow(new IOException("timeout"));

        LocatorHealer healer = new LocatorHealer(mockLlm, 1000);

        LocatorHealer.HealingResult result = healer.heal(
                new ElementInfo(), "", "http://test.com");

        assertThat(result.healed()).isFalse();
        assertThat(result.failureReason()).contains("timeout");
    }

    // ── DOM snippet truncation ────────────────────────────────────────────

    @Test
    public void heal_longPageSource_isTruncatedBeforeSending() throws IOException {
        LLMClient mockLlm = mock(LLMClient.class);
        // Capture the prompt sent to verify truncation
        final List<LLMClient.ChatMessage>[] captured = new List[1];
        when(mockLlm.complete(any())).thenAnswer(invocation -> {
            captured[0] = invocation.getArgument(0);
            return "#found";
        });

        int snippetChars = 50;
        LocatorHealer healer = new LocatorHealer(mockLlm, snippetChars);
        String longHtml = "X".repeat(500);

        healer.heal(new ElementInfo(), longHtml, "http://test.com");

        // The user prompt should contain the truncation marker
        String userContent = captured[0].stream()
                .filter(m -> "user".equals(m.role()))
                .findFirst()
                .map(LLMClient.ChatMessage::content)
                .orElse("");
        assertThat(userContent).contains("[TRUNCATED]");
    }

    // ── DOM comparison fallback — element with text ───────────────────────

    @Test
    public void domComparisonFallback_withText_returnsXpath() {
        LLMClient mockLlm = mock(LLMClient.class);
        LocatorHealer healer = new LocatorHealer(mockLlm, 1000);

        ElementInfo el = new ElementInfo();
        el.setTagName("button");
        el.setText("Submit Form");

        LocatorHealer.HealingResult result = healer.healByDomComparison(el, null);

        assertThat(result.healed()).isTrue();
        assertThat(result.strategy()).isEqualTo(ElementLocator.Strategy.XPATH);
        assertThat(result.locatorValue()).contains("button");
        assertThat(result.locatorValue()).contains("Submit Form");
    }

    // ── DOM fallback — text truncated at 30 chars ─────────────────────────

    @Test
    public void domComparisonFallback_longText_truncatesTo30Chars() {
        LLMClient mockLlm = mock(LLMClient.class);
        LocatorHealer healer = new LocatorHealer(mockLlm, 1000);

        ElementInfo el = new ElementInfo();
        el.setTagName("a");
        el.setText("A".repeat(100));

        LocatorHealer.HealingResult result = healer.healByDomComparison(el, null);

        assertThat(result.healed()).isTrue();
        // XPath should contain at most 30 A's (plus surrounding syntax)
        assertThat(result.locatorValue()).contains("A".repeat(30));
        assertThat(result.locatorValue()).doesNotContain("A".repeat(31));
    }

    // ── DOM fallback — wildcard tag when tagName is null ─────────────────

    @Test
    public void domComparisonFallback_nullTagName_usesWildcard() {
        LLMClient mockLlm = mock(LLMClient.class);
        LocatorHealer healer = new LocatorHealer(mockLlm, 1000);

        ElementInfo el = new ElementInfo();
        el.setTagName(null);
        el.setText("Click me");

        LocatorHealer.HealingResult result = healer.healByDomComparison(el, null);

        assertThat(result.healed()).isTrue();
        assertThat(result.locatorValue()).startsWith("//*[");
    }

    // ── DOM fallback — no text available ─────────────────────────────────

    @Test
    public void domComparisonFallback_noText_returnsFailure() {
        LLMClient mockLlm = mock(LLMClient.class);
        LocatorHealer healer = new LocatorHealer(mockLlm, 1000);

        ElementInfo el = new ElementInfo(); // text is null by default

        LocatorHealer.HealingResult result = healer.healByDomComparison(el, null);

        assertThat(result.healed()).isFalse();
        assertThat(result.failureReason()).contains("No text");
    }

    // ── DOM fallback — blank text ─────────────────────────────────────────

    @Test
    public void domComparisonFallback_blankText_returnsFailure() {
        LLMClient mockLlm = mock(LLMClient.class);
        LocatorHealer healer = new LocatorHealer(mockLlm, 1000);

        ElementInfo el = new ElementInfo();
        el.setText("   ");

        LocatorHealer.HealingResult result = healer.healByDomComparison(el, null);

        assertThat(result.healed()).isFalse();
    }

    // ── HealingResult factory accessors ──────────────────────────────────

    @Test
    public void healingResult_success_fieldsAreCorrect() {
        LocatorHealer.HealingResult r = LocatorHealer.HealingResult.success(
                "#btn", ElementLocator.Strategy.CSS);

        assertThat(r.healed()).isTrue();
        assertThat(r.locatorValue()).isEqualTo("#btn");
        assertThat(r.strategy()).isEqualTo(ElementLocator.Strategy.CSS);
        assertThat(r.failureReason()).isNull();
    }

    @Test
    public void healingResult_failed_fieldsAreCorrect() {
        LocatorHealer.HealingResult r = LocatorHealer.HealingResult.failed("timeout");

        assertThat(r.healed()).isFalse();
        assertThat(r.locatorValue()).isNull();
        assertThat(r.strategy()).isNull();
        assertThat(r.failureReason()).isEqualTo("timeout");
    }
}
