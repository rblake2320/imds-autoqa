package autoqa.reporting;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link JiraClient} using WireMock to stub the Jira REST API.
 *
 * <p>A single {@link WireMockServer} is started on a random ephemeral port for the
 * entire test class, keeping port collisions impossible in parallel test runs.
 */
public class JiraClientTest {

    private WireMockServer wireMock;

    @BeforeClass
    public void setup() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterClass
    public void teardown() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    // ── isConfigured ──────────────────────────────────────────────────────

    @Test
    public void isConfigured_blankUrl_returnsFalse() {
        JiraClient client = new JiraClient("", "user@example.com", "token123", "PROJ");
        assertThat(client.isConfigured()).isFalse();
    }

    @Test
    public void isConfigured_blankUsername_returnsFalse() {
        JiraClient client = new JiraClient("http://localhost", "", "token123", "PROJ");
        assertThat(client.isConfigured()).isFalse();
    }

    @Test
    public void isConfigured_blankApiToken_returnsFalse() {
        JiraClient client = new JiraClient("http://localhost", "user@example.com", "", "PROJ");
        assertThat(client.isConfigured()).isFalse();
    }

    @Test
    public void isConfigured_allFields_returnsTrue() {
        JiraClient client = new JiraClient(
                "https://myorg.atlassian.net", "user@example.com", "token123", "PROJ");
        assertThat(client.isConfigured()).isTrue();
    }

    // ── createBug ─────────────────────────────────────────────────────────

    @Test
    public void createBug_success_returnsIssueKey() {
        wireMock.stubFor(
                WireMock.post(WireMock.urlEqualTo("/rest/api/2/issue"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(201)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"key\":\"PROJ-99\",\"id\":\"10042\"}")));

        JiraClient client = new JiraClient(
                "http://localhost:" + wireMock.port(),
                "user@example.com",
                "apiToken",
                "PROJ");

        String key = client.createBug("Test failure in login flow",
                "Steps to reproduce...", "PROJ");

        assertThat(key).isEqualTo("PROJ-99");
    }

    @Test
    public void createBug_usesDefaultProjectKeyWhenNullProjectProvided() {
        wireMock.stubFor(
                WireMock.post(WireMock.urlEqualTo("/rest/api/2/issue"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(201)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"key\":\"AUTOQA-7\"}")));

        JiraClient client = new JiraClient(
                "http://localhost:" + wireMock.port(),
                "user@example.com",
                "apiToken",
                "AUTOQA");

        String key = client.createBug("Summary", "Desc", null);
        assertThat(key).isEqualTo("AUTOQA-7");
    }

    @Test
    public void createBug_serverError_returnsNull() {
        wireMock.stubFor(
                WireMock.post(WireMock.urlEqualTo("/rest/api/2/issue"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(500)
                                .withBody("Internal Server Error")));

        JiraClient client = new JiraClient(
                "http://localhost:" + wireMock.port(),
                "user@example.com",
                "apiToken",
                "PROJ");

        String key = client.createBug("Some summary", "Some description", "PROJ");
        assertThat(key).isNull();
    }

    @Test
    public void createBug_networkError_returnsNull() {
        // Point the client at a port where nothing is listening
        JiraClient client = new JiraClient(
                "http://localhost:1",   // port 1 — connection will be refused
                "user@example.com",
                "apiToken",
                "PROJ");

        String key = client.createBug("Unreachable summary", "Description", "PROJ");
        assertThat(key).isNull();
    }

    @Test
    public void createBug_notConfigured_returnsNull() {
        JiraClient client = new JiraClient("", "user", "token", "PROJ");
        // isConfigured() == false because baseUrl is blank
        String key = client.createBug("Summary", "Desc", "PROJ");
        assertThat(key).isNull();
    }

    // ── updateTestExecution ───────────────────────────────────────────────

    @Test
    public void updateTestExecution_success_noException() {
        wireMock.stubFor(
                WireMock.post(WireMock.urlEqualTo("/rest/api/2/issue/PROJ-1/comment"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(201)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"id\":\"100001\",\"body\":\"Test passed\"}")));

        JiraClient client = new JiraClient(
                "http://localhost:" + wireMock.port(),
                "user@example.com",
                "apiToken",
                "PROJ");

        // Must complete without throwing any exception
        assertThatCode(() -> client.updateTestExecution("PROJ-1", "Test passed"))
                .doesNotThrowAnyException();
    }

    @Test
    public void updateTestExecution_serverError_noException() {
        wireMock.stubFor(
                WireMock.post(WireMock.urlEqualTo("/rest/api/2/issue/PROJ-2/comment"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(500)
                                .withBody("Server Error")));

        JiraClient client = new JiraClient(
                "http://localhost:" + wireMock.port(),
                "user@example.com",
                "apiToken",
                "PROJ");

        // Should absorb the error gracefully
        assertThatCode(() -> client.updateTestExecution("PROJ-2", "Execution comment"))
                .doesNotThrowAnyException();
    }

    @Test
    public void updateTestExecution_networkError_noException() {
        JiraClient client = new JiraClient(
                "http://localhost:1",
                "user@example.com",
                "apiToken",
                "PROJ");

        assertThatCode(() -> client.updateTestExecution("PROJ-3", "Comment"))
                .doesNotThrowAnyException();
    }
}
