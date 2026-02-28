package autoqa.ai;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LLMClient} using WireMock to mock the Ollama HTTP endpoint.
 *
 * <p>A WireMockServer is started on a random ephemeral port before the test class
 * and stopped in the tear-down, so the tests are safe to run in parallel with other
 * suites that bind fixed ports.
 */
public class LLMClientTest {

    private WireMockServer wireMock;
    private LLMClient client;

    @BeforeClass
    public void setup() {
        wireMock = new WireMockServer(0); // 0 → random available port
        wireMock.start();

        // Point the client at the mock server; minimal retries for test speed
        client = new LLMClient(
                "http://localhost:" + wireMock.port() + "/v1",
                "test-model",
                0.1,
                100,
                10,  // timeoutSec
                1,   // retryCount
                100  // retryDelayMs — short delay in tests
        );
    }

    @AfterClass
    public void teardown() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    // ── Successful response ───────────────────────────────────────────────

    @Test
    public void complete_successfulResponse_returnsContent() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson("""
                        {"choices":[{"message":{"content":"#my-button","role":"assistant"}}]}
                        """)));

        String result = client.complete(List.of(LLMClient.ChatMessage.user("test")));
        assertThat(result).isEqualTo("#my-button");
    }

    // ── System message included ───────────────────────────────────────────

    @Test
    public void complete_withSystemAndUserMessages_returnsContent() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson("""
                        {"choices":[{"message":{"content":"//button[@id='submit']","role":"assistant"}}]}
                        """)));

        String result = client.complete(List.of(
                LLMClient.ChatMessage.system("You are a helpful assistant."),
                LLMClient.ChatMessage.user("Find the submit button.")));

        assertThat(result).isEqualTo("//button[@id='submit']");
    }

    // ── Content is trimmed ────────────────────────────────────────────────

    @Test
    public void complete_responseWithWhitespace_returnsTrimmedContent() throws Exception {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson("""
                        {"choices":[{"message":{"content":"  #btn  ","role":"assistant"}}]}
                        """)));

        String result = client.complete(List.of(LLMClient.ChatMessage.user("test")));
        assertThat(result).isEqualTo("#btn");
    }

    // ── 5xx triggers retry then throws ───────────────────────────────────

    @Test
    public void complete_500error_retriesAndThrows() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        assertThatThrownBy(() -> client.complete(List.of(LLMClient.ChatMessage.user("test"))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("attempt");
    }

    // ── 4xx is NOT retried — thrown immediately ───────────────────────────

    @Test
    public void complete_400error_throwsWithoutRetrying() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withBody("Bad Request")));

        assertThatThrownBy(() -> client.complete(List.of(LLMClient.ChatMessage.user("test"))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("400");
    }

    // ── Malformed JSON in response ────────────────────────────────────────

    @Test
    public void complete_malformedJson_throwsIOException() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("not-valid-json")));

        assertThatThrownBy(() -> client.complete(List.of(LLMClient.ChatMessage.user("test"))))
                .isInstanceOf(java.io.IOException.class);
    }

    // ── Missing choices array ─────────────────────────────────────────────

    @Test
    public void complete_emptyChoicesArray_throwsIOException() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson("""
                        {"choices":[]}
                        """)));

        assertThatThrownBy(() -> client.complete(List.of(LLMClient.ChatMessage.user("test"))))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("choices");
    }

    // ── ChatMessage factory methods ───────────────────────────────────────

    @Test
    public void chatMessage_systemFactory_setsCorrectRole() {
        LLMClient.ChatMessage msg = LLMClient.ChatMessage.system("hello");
        assertThat(msg.role()).isEqualTo("system");
        assertThat(msg.content()).isEqualTo("hello");
    }

    @Test
    public void chatMessage_userFactory_setsCorrectRole() {
        LLMClient.ChatMessage msg = LLMClient.ChatMessage.user("world");
        assertThat(msg.role()).isEqualTo("user");
        assertThat(msg.content()).isEqualTo("world");
    }

    // ── URL trailing-slash normalisation ─────────────────────────────────

    @Test
    public void complete_baseUrlWithTrailingSlash_stillWorksCorrectly() throws Exception {
        // Build a second client whose base URL has a trailing slash
        LLMClient slashClient = new LLMClient(
                "http://localhost:" + wireMock.port() + "/v1/",
                "test-model", 0.1, 100, 10, 0, 0);

        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(okJson("""
                        {"choices":[{"message":{"content":"result","role":"assistant"}}]}
                        """)));

        assertThat(slashClient.complete(List.of(LLMClient.ChatMessage.user("hi")))).isEqualTo("result");
    }
}
