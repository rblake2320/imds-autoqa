package autoqa.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Client for NVIDIA NV-CLIP 2.0.0 NIM — semantic image/text embedding model.
 *
 * <p>NV-CLIP produces embeddings for both images and text in a shared semantic space,
 * enabling:
 * <ul>
 *   <li>Image-to-text similarity: "does this screenshot show a login page?"</li>
 *   <li>Image-to-image similarity: "does this screenshot match the baseline?"</li>
 *   <li>Semantic visual regression: detect meaningful UI changes, not pixel noise</li>
 * </ul>
 *
 * <p>Requires the NV-CLIP NIM container running locally:
 * <pre>
 *   docker run -d --name nvclip \
 *     -e NGC_API_KEY=$NGC_API_KEY \
 *     -p 8000:8000 \
 *     nvcr.io/nim/nvidia/nvclip:2.0.0
 * </pre>
 *
 * <p>API endpoint: {@code POST http://localhost:8000/v1/embeddings}
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * NvClipClient clip = NvClipClient.local();
 *
 * // Semantic text-image similarity
 * double score = clip.imageTextSimilarity(driver, "login form with username and password fields");
 * assertThat(score).isGreaterThan(0.25); // threshold for a positive match
 *
 * // Visual regression: compare against baseline
 * byte[] baseline = Files.readAllBytes(Path.of("baseline/homepage.png"));
 * double sim = clip.imageImageSimilarity(driver, baseline);
 * assertThat(sim).isGreaterThan(0.90); // 90%+ semantic match
 * }</pre>
 */
public class NvClipClient {

    private static final Logger log = LoggerFactory.getLogger(NvClipClient.class);
    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Cosine similarity threshold above which screenshots are considered "matching". */
    public static final double DEFAULT_MATCH_THRESHOLD = 0.90;

    /** Cosine similarity threshold for text→image semantic match (less strict). */
    public static final double DEFAULT_TEXT_MATCH_THRESHOLD = 0.25;

    private final String endpoint;
    private final String apiKey;
    private final OkHttpClient http;

    // ── Factory methods ────────────────────────────────────────────────────────

    /** Creates a client pointing at the default local NIM container (localhost:8000). */
    public static NvClipClient local() {
        return new NvClipClient("http://localhost:8000/v1/embeddings", null);
    }

    /** Creates a client pointing at a custom endpoint with an API key. */
    public static NvClipClient of(String endpoint, String apiKey) {
        return new NvClipClient(endpoint, apiKey);
    }

    /** Creates a client from config.properties / environment variables. */
    public static NvClipClient fromConfig() {
        try (var is = NvClipClient.class.getResourceAsStream("/config.properties")) {
            var props = new java.util.Properties();
            if (is != null) props.load(is);
            String endpoint = props.getProperty("nvclip.endpoint", "http://localhost:8000/v1/embeddings");
            String envKey   = props.getProperty("nvclip.api.key.env", "NVIDIA_API_KEY");
            String apiKey   = System.getenv(envKey);
            return new NvClipClient(endpoint, apiKey);
        } catch (IOException e) {
            return local();
        }
    }

    private NvClipClient(String endpoint, String apiKey) {
        this.endpoint = endpoint;
        this.apiKey   = apiKey;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60,  TimeUnit.SECONDS)
                .build();
    }

    // ── Core API ──────────────────────────────────────────────────────────────

    /**
     * Computes the cosine similarity between the current page screenshot and
     * a text description. Useful for semantic assertions like "is this a checkout page?".
     *
     * @param driver      WebDriver for screenshot capture
     * @param description natural language description of expected page state
     * @return cosine similarity in [−1, 1]; values ≥ 0.25 generally indicate a match
     */
    public double imageTextSimilarity(WebDriver driver, String description) {
        byte[] png = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
        return imageTextSimilarity(png, description);
    }

    /**
     * Computes the cosine similarity between a PNG screenshot and a text description.
     */
    public double imageTextSimilarity(byte[] screenshotPng, String description) {
        try {
            double[] imgEmb  = embedImage(screenshotPng);
            double[] textEmb = embedText(description);
            return cosineSimilarity(imgEmb, textEmb);
        } catch (Exception e) {
            log.warn("NV-CLIP image-text similarity failed: {} — returning 0.0", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Computes the cosine similarity between two screenshots.
     * Values close to 1.0 mean visually/semantically identical pages.
     *
     * @param driver       WebDriver for current page screenshot
     * @param baselinePng  reference screenshot bytes (PNG)
     * @return cosine similarity in [−1, 1]; values ≥ 0.90 indicate a close match
     */
    public double imageImageSimilarity(WebDriver driver, byte[] baselinePng) {
        byte[] currentPng = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
        return imageImageSimilarity(currentPng, baselinePng);
    }

    /**
     * Computes the cosine similarity between two PNG byte arrays.
     */
    public double imageImageSimilarity(byte[] png1, byte[] png2) {
        try {
            double[] emb1 = embedImage(png1);
            double[] emb2 = embedImage(png2);
            return cosineSimilarity(emb1, emb2);
        } catch (Exception e) {
            log.warn("NV-CLIP image-image similarity failed: {} — returning 0.0", e.getMessage());
            return 0.0;
        }
    }

    /**
     * Asserts that the current page screenshot semantically matches the description.
     *
     * @throws AssertionError if similarity is below {@code threshold}
     */
    public void assertSemanticMatch(WebDriver driver, String description, double threshold) {
        double score = imageTextSimilarity(driver, description);
        if (score < threshold) {
            throw new AssertionError(String.format(
                    "NV-CLIP semantic match FAILED: score %.4f < threshold %.4f for description: \"%s\"",
                    score, threshold, description));
        }
        log.info("NV-CLIP semantic match PASSED: score={} >= threshold={} for \"{}\"",
                String.format("%.4f", score), String.format("%.4f", threshold), description);
    }

    /**
     * Asserts that the current screenshot matches the baseline with at least {@code threshold} similarity.
     *
     * @throws AssertionError if similarity is below threshold
     */
    public void assertVisualMatch(WebDriver driver, byte[] baselinePng, double threshold) {
        double score = imageImageSimilarity(driver, baselinePng);
        if (score < threshold) {
            throw new AssertionError(String.format(
                    "NV-CLIP visual regression FAILED: score %.4f < threshold %.4f (URL: %s)",
                    score, threshold, safeGetUrl(driver)));
        }
        log.info("NV-CLIP visual regression PASSED: score={} for {}", String.format("%.4f", score), safeGetUrl(driver));
    }

    // ── Embedding calls ───────────────────────────────────────────────────────

    /**
     * Gets an embedding vector for an image from the NV-CLIP NIM endpoint.
     */
    public double[] embedImage(byte[] png) throws IOException {
        String base64 = Base64.getEncoder().encodeToString(png);
        // NV-CLIP uses OpenAI-compatible embeddings API with image_url input type
        ObjectNode input = MAPPER.createObjectNode();
        input.put("type", "image_url");
        ObjectNode imageUrl = MAPPER.createObjectNode();
        imageUrl.put("url", "data:image/png;base64," + base64);
        input.set("image_url", imageUrl);

        return callEmbeddings(MAPPER.createArrayNode().add(input));
    }

    /**
     * Gets an embedding vector for text from the NV-CLIP NIM endpoint.
     */
    public double[] embedText(String text) throws IOException {
        return callEmbeddings(MAPPER.createArrayNode().add(text));
    }

    private double[] callEmbeddings(ArrayNode inputArray) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", "nvidia/nvclip");
        body.set("input", inputArray);

        Request.Builder rb = new Request.Builder()
                .url(endpoint)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(MAPPER.writeValueAsString(body), JSON_MT));

        if (apiKey != null && !apiKey.isBlank()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = http.newCall(rb.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("NV-CLIP API error: " + response.code() + " " +
                        (response.body() != null ? response.body().string() : response.message()));
            }
            String responseStr = response.body() != null ? response.body().string() : "{}";
            JsonNode json = MAPPER.readTree(responseStr);
            JsonNode embeddingNode = json.path("data").path(0).path("embedding");
            if (!embeddingNode.isArray()) {
                throw new IOException("NV-CLIP response missing embedding array. Response: " + responseStr);
            }
            double[] embedding = new double[embeddingNode.size()];
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] = embeddingNode.get(i).asDouble();
            }
            log.debug("NV-CLIP embedding dimension: {}", embedding.length);
            return embedding;
        }
    }

    // ── Math ──────────────────────────────────────────────────────────────────

    /**
     * Computes cosine similarity between two vectors.
     * Returns 0.0 if either vector has zero magnitude.
     */
    public static double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException(
                    "Vector dimensions must match: " + a.length + " vs " + b.length);
        }
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom < 1e-10 ? 0.0 : dot / denom;
    }

    private static String safeGetUrl(WebDriver driver) {
        try { return driver.getCurrentUrl(); } catch (Exception e) { return "(unavailable)"; }
    }
}
