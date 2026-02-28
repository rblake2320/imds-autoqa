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
 * Unit tests for {@link ConfluenceClient} using WireMock to stub Confluence Cloud REST API calls.
 *
 * <p>A single {@link WireMockServer} is started on a random ephemeral port before
 * all tests and stopped in tear-down, preventing port conflicts with parallel test suites.
 */
public class ConfluenceClientTest {

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
    public void isConfigured_missingApiToken_returnsFalse() {
        ConfluenceClient client = new ConfluenceClient(
                "https://myorg.atlassian.net", "user@example.com", "", "DEV", "");
        assertThat(client.isConfigured()).isFalse();
    }

    @Test
    public void isConfigured_missingBaseUrl_returnsFalse() {
        ConfluenceClient client = new ConfluenceClient(
                "", "user@example.com", "token", "DEV", "");
        assertThat(client.isConfigured()).isFalse();
    }

    @Test
    public void isConfigured_allFieldsSet_returnsTrue() {
        ConfluenceClient client = new ConfluenceClient(
                "https://myorg.atlassian.net", "user@example.com", "token123", "DEV", "98765");
        assertThat(client.isConfigured()).isTrue();
    }

    // ── createOrUpdatePage — new page ─────────────────────────────────────

    @Test
    public void createOrUpdatePage_newPage_postsCorrectly() {
        // GET search returns empty results — page does not exist
        wireMock.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/wiki/rest/api/content"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"results\":[],\"size\":0}")));

        // POST create returns success with the new page id
        wireMock.stubFor(
                WireMock.post(WireMock.urlEqualTo("/wiki/rest/api/content"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"id\":\"12345\",\"title\":\"AutoQA Report\"}")));

        ConfluenceClient client = new ConfluenceClient(
                "http://localhost:" + wireMock.port(),
                "user@example.com",
                "apiToken",
                "DEV",
                "99999");

        // Should complete without exception and trigger a POST (create)
        assertThatCode(() -> client.createOrUpdatePage(
                "AutoQA Report",
                "<h1>Results</h1>",
                "DEV",
                "99999"))
                .doesNotThrowAnyException();

        // Verify the POST was actually called
        wireMock.verify(1, WireMock.postRequestedFor(
                WireMock.urlEqualTo("/wiki/rest/api/content")));
    }

    // ── createOrUpdatePage — existing page ────────────────────────────────

    @Test
    public void createOrUpdatePage_existingPage_putsWithNewVersion() {
        // GET returns a page at version 3
        String existingPageResponse = "{"
                + "\"results\":[{"
                + "  \"id\":\"55555\","
                + "  \"title\":\"AutoQA Regression Report\","
                + "  \"version\":{\"number\":3}"
                + "}],"
                + "\"size\":1"
                + "}";

        wireMock.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/wiki/rest/api/content"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(existingPageResponse)));

        // PUT update returns success
        wireMock.stubFor(
                WireMock.put(WireMock.urlEqualTo("/wiki/rest/api/content/55555"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"id\":\"55555\",\"version\":{\"number\":4}}")));

        ConfluenceClient client = new ConfluenceClient(
                "http://localhost:" + wireMock.port(),
                "user@example.com",
                "apiToken",
                "DEV",
                "");

        assertThatCode(() -> client.createOrUpdatePage(
                "AutoQA Regression Report",
                "<p>Updated results</p>",
                "DEV",
                null))
                .doesNotThrowAnyException();

        // Verify a PUT was issued to the correct page id
        wireMock.verify(1, WireMock.putRequestedFor(
                WireMock.urlEqualTo("/wiki/rest/api/content/55555")));
    }

    // ── createOrUpdatePage — server error ─────────────────────────────────

    @Test
    public void createOrUpdatePage_serverError_logsNoException() {
        // GET returns 500
        wireMock.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/wiki/rest/api/content"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(500)
                                .withBody("Internal Server Error")));

        ConfluenceClient client = new ConfluenceClient(
                "http://localhost:" + wireMock.port(),
                "user@example.com",
                "apiToken",
                "DEV",
                "");

        // Error must be swallowed — no exception propagated to caller
        assertThatCode(() -> client.createOrUpdatePage(
                "Report With Server Error",
                "<p>content</p>",
                "DEV",
                null))
                .doesNotThrowAnyException();
    }

    // ── createOrUpdatePage — network failure ──────────────────────────────

    @Test
    public void createOrUpdatePage_networkError_logsNoException() {
        ConfluenceClient client = new ConfluenceClient(
                "http://localhost:1",  // nothing listening on port 1
                "user@example.com",
                "apiToken",
                "DEV",
                "");

        assertThatCode(() -> client.createOrUpdatePage(
                "Unreachable Page", "<p>body</p>", "DEV", null))
                .doesNotThrowAnyException();
    }

    // ── createOrUpdatePage — not configured ───────────────────────────────

    @Test
    public void createOrUpdatePage_notConfigured_doesNothing() {
        // Reset all recorded requests so the verify below reflects only this test's activity
        wireMock.resetRequests();

        ConfluenceClient client = new ConfluenceClient(
                "", "user@example.com", "token", "DEV", "");

        // isConfigured() == false; no HTTP call should be made and no exception thrown
        assertThatCode(() -> client.createOrUpdatePage(
                "Some Page", "<p>content</p>", "DEV", null))
                .doesNotThrowAnyException();

        // After resetRequests() above, zero requests should have been received
        wireMock.verify(0, WireMock.getRequestedFor(
                WireMock.urlPathMatching("/wiki/rest/api/content.*")));
    }

    // ── createOrUpdatePage — uses default space key ───────────────────────

    @Test
    public void createOrUpdatePage_nullSpaceKey_usesDefault() {
        wireMock.stubFor(
                WireMock.get(WireMock.urlPathEqualTo("/wiki/rest/api/content"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"results\":[],\"size\":0}")));

        wireMock.stubFor(
                WireMock.post(WireMock.urlEqualTo("/wiki/rest/api/content"))
                        .willReturn(WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"id\":\"77777\",\"title\":\"Default Space Page\"}")));

        // defaultSpaceKey = "MYSPACE"; pass null for spaceKey — should use default
        ConfluenceClient client = new ConfluenceClient(
                "http://localhost:" + wireMock.port(),
                "user@example.com",
                "apiToken",
                "MYSPACE",
                "");

        assertThatCode(() -> client.createOrUpdatePage(
                "Default Space Page", "<p>hello</p>", null, null))
                .doesNotThrowAnyException();

        // GET must contain spaceKey=MYSPACE in the query string
        wireMock.verify(WireMock.getRequestedFor(
                WireMock.urlPathEqualTo("/wiki/rest/api/content"))
                .withQueryParam("spaceKey", WireMock.equalTo("MYSPACE")));
    }
}
