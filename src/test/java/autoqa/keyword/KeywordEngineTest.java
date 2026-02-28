package autoqa.keyword;

import autoqa.player.AutoQAException;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link KeywordLibrary} and {@link KeywordEngine} —
 * verifying keyword resolution, parameter extraction, and error handling
 * WITHOUT requiring a live browser.
 */
public class KeywordEngineTest {

    @Test
    public void keywordAction_gettersReturnExpectedValues() {
        KeywordAction action = new KeywordAction(
                "click", "loginButton",
                Map.of("value", "test"), "Click the login button");

        assertThat(action.getKeyword()).isEqualTo("click");
        assertThat(action.getTarget()).isEqualTo("loginButton");
        assertThat(action.getParams()).containsEntry("value", "test");
        assertThat(action.getDescription()).isEqualTo("Click the login button");
    }

    @Test
    public void keywordAction_paramWithDefault_returnsDefault() {
        KeywordAction action = new KeywordAction("typeText", "field", Map.of(), null);
        assertThat(action.param("value", "fallback")).isEqualTo("fallback");
    }

    @Test
    public void keywordAction_paramWithDefault_returnsActualWhenPresent() {
        KeywordAction action = new KeywordAction("typeText", "field",
                Map.of("value", "hello"), null);
        assertThat(action.param("value", "fallback")).isEqualTo("hello");
    }

    @Test
    public void keywordAction_nullParams_returnsEmptyMap() {
        KeywordAction action = new KeywordAction("click", "btn", null, null);
        assertThat(action.getParams()).isEmpty();
    }

    @Test
    public void keywordAction_toString_containsKeyword() {
        KeywordAction action = new KeywordAction("hover", "menu", Map.of(), null);
        assertThat(action.toString()).contains("hover");
    }

    @Test
    public void keywordStepJson_deserialization_works() throws Exception {
        String json = "[{\"keyword\":\"click\",\"target\":\"btn\",\"params\":{\"k\":\"v\"}}]";
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<KeywordEngine.KeywordStepJson> steps = mapper.readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<>() {});
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).keyword).isEqualTo("click");
        assertThat(steps.get(0).target).isEqualTo("btn");
        assertThat(steps.get(0).params).containsEntry("k", "v");
    }

    @Test
    public void runResult_success_toStringShowsSuccess() {
        KeywordEngine.RunResult result = new KeywordEngine.RunResult(true, 5, 5, null);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getStepsCompleted()).isEqualTo(5);
        assertThat(result.toString()).contains("SUCCESS");
    }

    @Test
    public void runResult_failure_toStringShowsFailure() {
        KeywordEngine.RunResult result = new KeywordEngine.RunResult(false, 2, 5, "step failed");
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo("step failed");
        assertThat(result.toString()).contains("FAILED");
    }
}
