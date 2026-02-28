package autoqa.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link ApiClient}, {@link ApiRequest}, {@link ApiResponse},
 * and {@link ApiAssertion} using WireMock as the HTTP mock server.
 *
 * <p>All tests run in the same WireMock server instance started in {@link #startWireMock()}
 * and stopped in {@link #stopWireMock()}.</p>
 */
public class ApiClientTest {

    private WireMockServer wireMock;
    private ApiClient      client;

    /** Base URL of the mock server, set once the random port is known. */
    private String base;

    // ── lifecycle ──────────────────────────────────────────────────────────

    @BeforeClass
    public void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        base   = "http://localhost:" + wireMock.port();
        client = ApiClient.create();
    }

    @AfterClass
    public void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    // ── Test 1: GET 200 with plain-text body ───────────────────────────────

    @Test
    public void get200WithBody() {
        wireMock.stubFor(get(urlEqualTo("/hello"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("Hello, World!")));

        ApiResponse response = client.send(
                ApiRequest.get(base + "/hello").build());

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("Hello, World!");
        assertThat(response.isSuccess()).isTrue();
    }

    // ── Test 2: POST 201 with JSON body ───────────────────────────────────

    @Test
    public void post201WithJsonBody() {
        String requestJson  = "{\"name\":\"Alice\"}";
        String responseJson = "{\"id\":42,\"name\":\"Alice\"}";

        wireMock.stubFor(post(urlEqualTo("/users"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(equalToJson(requestJson))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseJson)));

        ApiResponse response = client.send(
                ApiRequest.post(base + "/users", requestJson)
                          .contentType("application/json")
                          .build());

        assertThat(response.getStatusCode()).isEqualTo(201);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getBody()).isEqualTo(responseJson);
    }

    // ── Test 3: statusCode assertion — pass then fail ─────────────────────

    @Test
    public void statusCodeAssertionPassAndFail() {
        wireMock.stubFor(get(urlEqualTo("/status"))
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        ApiResponse response = client.send(ApiRequest.get(base + "/status").build());

        // Should pass without exception
        client.assertThat(response).statusCode(200);

        // Should throw AssertionError for wrong code
        assertThatThrownBy(() -> client.assertThat(response).statusCode(404))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("200")
                .hasMessageContaining("404");
    }

    // ── Test 4: bodyContains assertion ────────────────────────────────────

    @Test
    public void bodyContainsAssertion() {
        wireMock.stubFor(get(urlEqualTo("/items"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("[\"apple\",\"banana\",\"cherry\"]")));

        ApiResponse response = client.send(ApiRequest.get(base + "/items").build());

        // Case-insensitive pass
        client.assertThat(response).bodyContains("BANANA");

        // Fail path — text not in body
        assertThatThrownBy(() -> client.assertThat(response).bodyContains("mango"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("mango");
    }

    // ── Test 5: durationBelow performance assertion ───────────────────────

    @Test
    public void durationBelowAssertion() {
        wireMock.stubFor(get(urlEqualTo("/fast"))
                .willReturn(aResponse().withStatus(200).withBody("fast")));

        ApiResponse response = client.send(ApiRequest.get(base + "/fast").build());

        // Should comfortably finish under 5 seconds on any machine
        client.assertThat(response).durationBelow(5_000L);

        // Negative: assert that it fails when the limit is 0 ms
        assertThatThrownBy(() -> client.assertThat(response).durationBelow(0L))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ms");
    }

    // ── Test 6: basicAuth header encoding ─────────────────────────────────

    @Test
    public void basicAuthHeaderIsBase64Encoded() {
        String user = "admin";
        String pass = "s3cr3t";
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));

        wireMock.stubFor(get(urlEqualTo("/secure"))
                .withHeader("Authorization", equalTo(expected))
                .willReturn(aResponse().withStatus(200).withBody("authorized")));

        ApiResponse response = client.send(
                ApiRequest.get(base + "/secure")
                          .basicAuth(user, pass)
                          .build());

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("authorized");

        // Also verify the header appears on the request object
        assertThat(response.getRequest().getHeaders())
                .containsEntry("Authorization", expected);
    }

    // ── Test 7: getJsonValue dot-notation navigation ──────────────────────

    @Test
    public void getJsonValueDotNotationNavigation() {
        String json = "{\"data\":{\"user\":{\"id\":99,\"role\":\"admin\"}}}";

        wireMock.stubFor(get(urlEqualTo("/profile"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));

        ApiResponse response = client.send(ApiRequest.get(base + "/profile").build());

        assertThat(response.getJsonValue("data.user.id")).isEqualTo("99");
        assertThat(response.getJsonValue("data.user.role")).isEqualTo("admin");
        assertThat(response.getJsonValue("data.user.missing")).isNull();
        assertThat(response.getJsonValue("nonexistent")).isNull();
    }

    // ── Test 8: header assertion ───────────────────────────────────────────

    @Test
    public void headerAssertion() {
        wireMock.stubFor(get(urlEqualTo("/versioned"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("X-Api-Version", "v3")
                        .withHeader("Content-Type", "application/json")
                        .withBody("{}")));

        ApiResponse response = client.send(ApiRequest.get(base + "/versioned").build());

        // Pass: header present with correct value (case-insensitive name lookup)
        client.assertThat(response).header("x-api-version", "v3");

        // Fail: header value mismatch
        assertThatThrownBy(() -> client.assertThat(response).header("x-api-version", "v99"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("v3")
                .hasMessageContaining("v99");

        // Fail: header absent
        assertThatThrownBy(() -> client.assertThat(response).header("X-Missing-Header", "anything"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("X-Missing-Header");
    }

    // ── Test bonus: statusCodeBetween + bodyMatchesJson ────────────────────

    @Test
    public void statusCodeBetweenAndBodyMatchesJson() {
        String json = "{ \"status\": \"ok\", \"code\": 1 }";

        wireMock.stubFor(get(urlEqualTo("/health"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(json)));

        ApiResponse response = client.send(ApiRequest.get(base + "/health").build());

        // Range assertion
        client.assertThat(response).statusCodeBetween(200, 299);

        // Structural JSON equality (whitespace/order-insensitive)
        client.assertThat(response).bodyMatchesJson("{\"code\":1,\"status\":\"ok\"}");

        // Fail on structural mismatch
        assertThatThrownBy(() -> client.assertThat(response)
                .bodyMatchesJson("{\"status\":\"error\"}"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("JSON bodies do not match");
    }
}
