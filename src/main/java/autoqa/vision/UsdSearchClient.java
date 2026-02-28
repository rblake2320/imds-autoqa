package autoqa.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Client for NVIDIA USD Search API 1.2 — hybrid natural-language and image search
 * over 3D USD assets, scenes, and rendered image collections.
 *
 * <p>In a test automation context, USD Search is useful for:
 * <ul>
 *   <li><b>3D UI testing</b>: searching for UI elements in Omniverse-based applications
 *       rendered as USD scenes</li>
 *   <li><b>Asset verification</b>: confirming that specific 3D assets appear in
 *       a rendered frame using image-based search</li>
 *   <li><b>Semantic screenshot search</b>: finding similar rendered states across
 *       a test asset library using natural language ("show the error dialog state")</li>
 * </ul>
 *
 * <p>USD Search 1.2 uses NV-CLIP embeddings for image-based search, making it
 * the backend query service that complements the client-side {@link NvClipClient}.
 *
 * <p>Requires USD Search API deployed via Helm on Kubernetes, or locally via Docker.
 * Default endpoint: {@code http://localhost:8080}
 *
 * <p>If USD Search is not available (no Omniverse/3D assets), this class is a no-op;
 * all methods return empty results. Use {@link #isAvailable()} to check.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * UsdSearchClient search = UsdSearchClient.local();
 * if (search.isAvailable()) {
 *     List<SearchResult> results = search.searchByText("login screen with error message");
 *     List<SearchResult> similar = search.searchByImage(driver, 5);
 * }
 * }</pre>
 */
public class UsdSearchClient {

    private static final Logger log = LoggerFactory.getLogger(UsdSearchClient.class);
    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String endpoint;
    private final String apiKey;
    private final OkHttpClient http;
    private Boolean availabilityCache = null;

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Creates a client pointing at the default local USD Search endpoint (localhost:8080). */
    public static UsdSearchClient local() {
        return new UsdSearchClient("http://localhost:8080", null);
    }

    /** Creates a client with a custom endpoint and API key. */
    public static UsdSearchClient of(String endpoint, String apiKey) {
        return new UsdSearchClient(endpoint, apiKey);
    }

    private UsdSearchClient(String endpoint, String apiKey) {
        this.endpoint = endpoint;
        this.apiKey   = apiKey;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30,  TimeUnit.SECONDS)
                .build();
    }

    // ── Availability check ────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the USD Search API is reachable.
     * Result is cached after the first call.
     */
    public boolean isAvailable() {
        if (availabilityCache != null) return availabilityCache;
        try {
            Request req = new Request.Builder()
                    .url(endpoint + "/health")
                    .get().build();
            try (Response resp = http.newCall(req).execute()) {
                availabilityCache = resp.isSuccessful();
            }
        } catch (Exception e) {
            log.debug("USD Search API not reachable at {}: {}", endpoint, e.getMessage());
            availabilityCache = false;
        }
        return availabilityCache;
    }

    // ── Search API ────────────────────────────────────────────────────────────

    /**
     * Searches the asset database using a natural language query (hybrid text + vector search).
     *
     * @param query  natural language description (e.g. "login dialog with red error banner")
     * @return list of matching asset results; empty if unavailable or no matches
     */
    public List<SearchResult> searchByText(String query) {
        return searchByText(query, 10);
    }

    public List<SearchResult> searchByText(String query, int limit) {
        if (!isAvailable()) return List.of();
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("description", query);
            body.put("limit",       limit);
            body.put("return_images", true);

            String responseJson = post("/search", body.toString());
            return parseResults(responseJson);
        } catch (Exception e) {
            log.warn("USD Search text query failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Searches the asset database using the current page screenshot as the query image.
     *
     * @param driver   WebDriver for screenshot capture
     * @param limit    maximum number of results
     * @return list of visually similar assets; empty if unavailable
     */
    public List<SearchResult> searchByImage(WebDriver driver, int limit) {
        if (!isAvailable()) return List.of();
        try {
            byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            String base64 = Base64.getEncoder().encodeToString(png);

            ObjectNode body = MAPPER.createObjectNode();
            body.put("image_url", "data:image/png;base64," + base64);
            body.put("limit",     limit);
            body.put("return_images", true);

            String responseJson = post("/search", body.toString());
            return parseResults(responseJson);
        } catch (Exception e) {
            log.warn("USD Search image query failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Performs a hybrid search (combines text and image queries with configurable weights).
     * USD Search 1.2 hybrid search feature.
     *
     * @param query       natural language query (weight controlled by {@code textWeight})
     * @param driver      WebDriver for screenshot (image query)
     * @param textWeight  0.0–1.0 weight for text vs image (0.5 = balanced)
     */
    public List<SearchResult> hybridSearch(String query, WebDriver driver,
                                            double textWeight, int limit) {
        if (!isAvailable()) return List.of();
        try {
            byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            String base64 = Base64.getEncoder().encodeToString(png);

            ObjectNode body = MAPPER.createObjectNode();
            body.put("description", query);
            body.put("image_url",   "data:image/png;base64," + base64);
            body.put("text_weight", textWeight);
            body.put("limit",       limit);
            body.put("return_images", true);

            String responseJson = post("/hybrid_search", body.toString());
            return parseResults(responseJson);
        } catch (Exception e) {
            log.warn("USD Search hybrid query failed: {}", e.getMessage());
            return List.of();
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private String post(String path, String json) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url(endpoint + path)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json, JSON_MT));
        if (apiKey != null && !apiKey.isBlank()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }
        try (Response response = http.newCall(rb.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("USD Search API error: " + response.code());
            }
            return response.body() != null ? response.body().string() : "{}";
        }
    }

    private List<SearchResult> parseResults(String json) {
        List<SearchResult> results = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode items = root.path("results");
            if (!items.isArray()) items = root.path("items");
            if (!items.isArray()) return results;

            for (JsonNode item : items) {
                results.add(new SearchResult(
                        item.path("url").asText(""),
                        item.path("name").asText(""),
                        item.path("score").asDouble(0.0),
                        item.path("image_url").asText(null),
                        item.path("description").asText("")
                ));
            }
        } catch (Exception e) {
            log.debug("USD Search parse failed: {}", e.getMessage());
        }
        return results;
    }

    // ── Result record ──────────────────────────────────────────────────────────

    /**
     * A single result from USD Search API.
     */
    public record SearchResult(String url, String name, double score,
                                String thumbnailUrl, String description) {
        @Override
        public String toString() {
            return String.format("SearchResult{score=%.4f, name='%s', url='%s'}", score, name, url);
        }
    }
}
