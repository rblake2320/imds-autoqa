package autoqa.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link APIServer}.
 *
 * <p>A real {@link APIServer} is started on a random available port before the test
 * class runs; it is shut down after. Tests use {@code java.net.http.HttpClient} to
 * exercise all 10 HTTP endpoints.
 *
 * <p>Background-process endpoints ({@code record/start}, {@code play}, etc.) are tested
 * for correct response shape and state transitions without launching a real JVM subprocess.
 */
public class APIServerTest {

    private static APIServer server;
    private static int port;
    private static HttpClient http;
    private static ObjectMapper mapper;
    private static String baseUrl;

    @BeforeClass
    public static void startServer() throws Exception {
        port = findFreePort();
        server = new APIServer(port);
        server.start();

        http   = HttpClient.newHttpClient();
        mapper = new ObjectMapper();
        baseUrl = "http://localhost:" + port;
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> options(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }

    // ── CORS ──────────────────────────────────────────────────────────────

    @Test(description = "Every endpoint includes CORS Access-Control-Allow-Origin: * header")
    public void corsHeader_presentOnStatusEndpoint() throws Exception {
        HttpResponse<String> resp = get("/api/status");

        assertThat(resp.headers().firstValue("Access-Control-Allow-Origin"))
                .isPresent()
                .hasValue("*");
    }

    @Test(description = "OPTIONS preflight returns 200 with CORS headers")
    public void corsPreflightOptions_returns200() throws Exception {
        HttpResponse<String> resp = options("/api/status");

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.headers().firstValue("Access-Control-Allow-Methods"))
                .isPresent()
                .hasValueSatisfying(v -> assertThat(v).contains("GET", "POST"));
    }

    @Test(description = "CORS header present on /api/recordings endpoint")
    public void corsHeader_presentOnRecordingsEndpoint() throws Exception {
        HttpResponse<String> resp = get("/api/recordings");

        assertThat(resp.headers().firstValue("Access-Control-Allow-Origin"))
                .isPresent()
                .hasValue("*");
    }

    @Test(description = "CORS header present on /api/log endpoint")
    public void corsHeader_presentOnLogEndpoint() throws Exception {
        HttpResponse<String> resp = get("/api/log");

        assertThat(resp.headers().firstValue("Access-Control-Allow-Origin"))
                .isPresent()
                .hasValue("*");
    }

    // ── /api/status ───────────────────────────────────────────────────────

    @Test(description = "GET /api/status returns 200 with state=idle initially")
    public void status_returns200_withIdleState() throws Exception {
        HttpResponse<String> resp = get("/api/status");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.has("state")).isTrue();
        assertThat(body.get("state").asText()).isEqualTo("idle");
    }

    @Test(description = "GET /api/status response has recording and lastRecording fields")
    public void status_hasRequiredFields() throws Exception {
        HttpResponse<String> resp = get("/api/status");
        JsonNode body = json(resp);

        assertThat(body.has("state")).isTrue();
        assertThat(body.has("recording")).isTrue();
        assertThat(body.has("lastRecording")).isTrue();
    }

    @Test(description = "POST /api/status returns 405 Method Not Allowed")
    public void status_postReturns405() throws Exception {
        HttpResponse<String> resp = post("/api/status", "{}");

        assertThat(resp.statusCode()).isEqualTo(405);
    }

    // ── /api/recordings ───────────────────────────────────────────────────

    @Test(description = "GET /api/recordings returns 200 with a JSON array")
    public void recordings_returnsJsonArray() throws Exception {
        HttpResponse<String> resp = get("/api/recordings");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.isArray()).isTrue();
    }

    @Test(description = "GET /api/recordings returns Content-Type: application/json")
    public void recordings_contentTypeIsJson() throws Exception {
        HttpResponse<String> resp = get("/api/recordings");

        assertThat(resp.headers().firstValue("Content-Type"))
                .isPresent()
                .hasValueSatisfying(ct -> assertThat(ct).contains("application/json"));
    }

    // ── /api/log ──────────────────────────────────────────────────────────

    @Test(description = "GET /api/log returns 200 with a JSON array of log lines")
    public void log_returnsJsonArray() throws Exception {
        HttpResponse<String> resp = get("/api/log");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.isArray()).isTrue();
    }

    @Test(description = "POST /api/log returns 405 Method Not Allowed")
    public void log_postReturns405() throws Exception {
        HttpResponse<String> resp = post("/api/log", "{}");

        assertThat(resp.statusCode()).isEqualTo(405);
    }

    // ── /api/stop ─────────────────────────────────────────────────────────

    @Test(description = "POST /api/stop returns 200 with ok=true and state=idle")
    public void stop_returns200_withIdleState() throws Exception {
        HttpResponse<String> resp = post("/api/stop", "{}");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("state").asText()).isEqualTo("idle");
    }

    @Test(description = "GET /api/stop returns 405 Method Not Allowed")
    public void stop_getReturns405() throws Exception {
        HttpResponse<String> resp = get("/api/stop");

        assertThat(resp.statusCode()).isEqualTo(405);
    }

    // ── /api/play ─────────────────────────────────────────────────────────

    @Test(description = "POST /api/play without 'recording' field returns 400")
    public void play_missingRecording_returns400() throws Exception {
        HttpResponse<String> resp = post("/api/play", "{}");

        assertThat(resp.statusCode()).isEqualTo(400);
        JsonNode body = json(resp);
        assertThat(body.has("error")).isTrue();
        assertThat(body.get("error").asText()).containsIgnoringCase("recording");
    }

    @Test(description = "POST /api/play with empty recording field returns 400")
    public void play_emptyRecording_returns400() throws Exception {
        HttpResponse<String> resp = post("/api/play", "{\"recording\":\"\"}");

        assertThat(resp.statusCode()).isEqualTo(400);
    }

    @Test(description = "POST /api/play with recording field returns 200 and playing state")
    public void play_withRecording_returns200() throws Exception {
        HttpResponse<String> resp = post("/api/play", "{\"recording\":\"test.json\"}");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("state").asText()).isEqualTo("playing");
    }

    // ── /api/generate ─────────────────────────────────────────────────────

    @Test(description = "POST /api/generate without 'recording' returns 400")
    public void generate_missingRecording_returns400() throws Exception {
        HttpResponse<String> resp = post("/api/generate", "{}");

        assertThat(resp.statusCode()).isEqualTo(400);
    }

    @Test(description = "POST /api/generate with recording returns 200 and generating state")
    public void generate_withRecording_returns200() throws Exception {
        HttpResponse<String> resp = post("/api/generate", "{\"recording\":\"test.json\"}");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("state").asText()).isEqualTo("generating");
    }

    // ── /api/heal ─────────────────────────────────────────────────────────

    @Test(description = "POST /api/heal without 'recording' returns 400")
    public void heal_missingRecording_returns400() throws Exception {
        HttpResponse<String> resp = post("/api/heal", "{}");

        assertThat(resp.statusCode()).isEqualTo(400);
    }

    @Test(description = "POST /api/heal with recording returns 200 and healing state")
    public void heal_withRecording_returns200() throws Exception {
        HttpResponse<String> resp = post("/api/heal", "{\"recording\":\"test.json\"}");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("state").asText()).isEqualTo("healing");
    }

    // ── /api/record/start ─────────────────────────────────────────────────

    @Test(description = "POST /api/record/start without 'url' returns 400")
    public void recordStart_missingUrl_returns400() throws Exception {
        HttpResponse<String> resp = post("/api/record/start", "{\"name\":\"my-flow\"}");

        assertThat(resp.statusCode()).isEqualTo(400);
        JsonNode body = json(resp);
        assertThat(body.get("error").asText()).containsIgnoringCase("url");
    }

    @Test(description = "POST /api/record/start with url returns 200 and recording state")
    public void recordStart_withUrl_returns200() throws Exception {
        HttpResponse<String> resp = post("/api/record/start",
                "{\"url\":\"https://example.com\",\"name\":\"test-flow\"}");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("state").asText()).isEqualTo("recording");
    }

    // ── /api/record/stop ──────────────────────────────────────────────────

    @Test(description = "POST /api/record/stop returns 200 with idle state")
    public void recordStop_returns200_withIdleState() throws Exception {
        HttpResponse<String> resp = post("/api/record/stop", "{}");

        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = json(resp);
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("state").asText()).isEqualTo("idle");
    }

    @Test(description = "POST /api/record/stop response includes path field")
    public void recordStop_responseHasPathField() throws Exception {
        HttpResponse<String> resp = post("/api/record/stop", "{}");
        JsonNode body = json(resp);

        assertThat(body.has("path")).isTrue();
    }

    // ── /api/recordings/{name}/content ────────────────────────────────────

    @Test(description = "GET /api/recordings/nonexistent.json/content returns 404")
    public void recordingContent_notFound_returns404() throws Exception {
        HttpResponse<String> resp = get("/api/recordings/nonexistent-recording-99.json/content");

        assertThat(resp.statusCode()).isEqualTo(404);
    }

    @Test(description = "GET /api/recordings/{name}/content with existing file returns 200")
    public void recordingContent_existingFile_returns200() throws Exception {
        // Create a test recording file in the expected location
        Path dir = Paths.get("D:/imds-autoqa/recordings");
        Files.createDirectories(dir);
        Path testFile = dir.resolve("api-test-recording.json");
        Files.writeString(testFile, "{\"sessionId\":\"test-123\",\"events\":[]}");

        try {
            HttpResponse<String> resp = get("/api/recordings/api-test-recording.json/content");
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("test-123");
        } finally {
            Files.deleteIfExists(testFile);
        }
    }

    // ── Invalid JSON body ─────────────────────────────────────────────────

    @Test(description = "POST with invalid JSON body returns 400")
    public void invalidJson_returns400() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/play"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("not-valid-json{{{"))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(400);
        JsonNode body = json(resp);
        assertThat(body.has("error")).isTrue();
    }

    // ── State transitions ─────────────────────────────────────────────────

    @Test(description = "State transitions correctly: stop brings state back to idle")
    public void stateTransition_playThenStop() throws Exception {
        // Start a play operation
        post("/api/play", "{\"recording\":\"dummy.json\"}");

        // Stop should reset to idle
        HttpResponse<String> stopResp = post("/api/stop", "{}");
        assertThat(stopResp.statusCode()).isEqualTo(200);

        JsonNode body = json(stopResp);
        assertThat(body.get("state").asText()).isEqualTo("idle");
    }
}
