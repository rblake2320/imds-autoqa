package autoqa.vision;

import autoqa.model.BoundingBox;
import autoqa.model.UIElement;
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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * VisionService backed by NVIDIA NIM (e.g., phi-3-vision-128k-instruct).
 * Takes a screenshot, base64-encodes it, POSTs to the NIM endpoint,
 * and parses the JSON response into UIElement objects.
 */
public class NvidiaVisionClient implements VisionService {
    private static final Logger log = LoggerFactory.getLogger(NvidiaVisionClient.class);
    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String endpoint;
    private final String apiKey;
    private final int timeoutSec;
    private final double minConfidence;
    private final OkHttpClient http;

    public NvidiaVisionClient(String endpoint, String apiKey, int timeoutSec, double minConfidence) {
        this.endpoint      = endpoint;
        this.apiKey        = apiKey;
        this.timeoutSec    = timeoutSec;
        this.minConfidence = minConfidence;
        this.http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .build();
    }

    /** Factory: create from config.properties + env var for API key. */
    public static NvidiaVisionClient fromConfig() {
        Properties props = new Properties();
        try (InputStream is = NvidiaVisionClient.class.getResourceAsStream("/config.properties")) {
            if (is != null) props.load(is);
        } catch (IOException e) { /* use defaults */ }

        String envKey = props.getProperty("vision.nim.api.key.env", "NVIDIA_API_KEY");
        String apiKey = System.getenv(envKey);
        if (apiKey == null) apiKey = "MISSING_NVIDIA_API_KEY";

        return new NvidiaVisionClient(
            props.getProperty("vision.nim.endpoint",
                "https://ai.api.nvidia.com/v1/vlm/microsoft/phi-3-vision-128k-instruct"),
            apiKey,
            Integer.parseInt(props.getProperty("vision.nim.timeout.sec", "30")),
            Double.parseDouble(props.getProperty("vision.min.confidence", "0.75"))
        );
    }

    @Override
    public List<UIElement> analyzeScreenshot(WebDriver driver) {
        try {
            byte[] png = takeScreenshot(driver);
            String base64 = Base64.getEncoder().encodeToString(png);
            String responseText = callNim(base64, buildAnalysisPrompt());
            return parseElements(responseText);
        } catch (Exception e) {
            log.warn("Vision analysis failed: {} — returning empty list", e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean isPopupPresent(WebDriver driver) {
        try {
            byte[] png = takeScreenshot(driver);
            String base64 = Base64.getEncoder().encodeToString(png);
            String response = callNim(base64,
                "Is there a popup, modal dialog, or alert overlay visible? Answer YES or NO only.");
            return response.trim().toUpperCase().startsWith("YES");
        } catch (Exception e) {
            log.warn("Vision popup check failed: {} — returning false", e.getMessage());
            return false;
        }
    }

    private byte[] takeScreenshot(WebDriver driver) {
        return ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
    }

    private String callNim(String base64Image, String prompt) throws IOException {
        ObjectNode imageUrl = MAPPER.createObjectNode();
        imageUrl.put("url", "data:image/png;base64," + base64Image);

        ObjectNode textPart = MAPPER.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", prompt);

        ObjectNode imagePart = MAPPER.createObjectNode();
        imagePart.put("type", "image_url");
        imagePart.set("image_url", imageUrl);

        ObjectNode message = MAPPER.createObjectNode();
        message.put("role", "user");
        message.set("content", MAPPER.createArrayNode().add(textPart).add(imagePart));

        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", "microsoft/phi-3-vision-128k-instruct");
        body.set("messages", MAPPER.createArrayNode().add(message));
        body.put("max_tokens", 1024);

        Request request = new Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .post(RequestBody.create(MAPPER.writeValueAsString(body), JSON_MT))
            .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("NIM API error: " + response.code() + " " + response.message());
            }
            JsonNode respNode = MAPPER.readTree(response.body().string());
            return respNode.path("choices").path(0).path("message").path("content").asText();
        }
    }

    private String buildAnalysisPrompt() {
        return """
            Analyze this screenshot and list all interactive UI elements.
            For each element, provide: type, visible text, and approximate position.
            Format: TYPE|TEXT|X,Y,WIDTH,HEIGHT on separate lines.
            Types: button, input, select, link, modal, alert, checkbox, radio
            Example: button|Submit|100,200,80,36
            List only clearly visible interactive elements:
            """;
    }

    private List<UIElement> parseElements(String responseText) {
        List<UIElement> elements = new ArrayList<>();
        for (String line : responseText.split("\\n")) {
            line = line.trim();
            if (line.isEmpty() || !line.contains("|")) continue;
            String[] parts = line.split("\\|");
            if (parts.length < 2) continue;

            UIElement el = new UIElement();
            el.setType(parts[0].trim().toLowerCase());
            el.setText(parts.length > 1 ? parts[1].trim() : null);
            el.setConfidence(0.8); // NIM doesn't return confidence; use default

            if (parts.length > 2) {
                String[] coords = parts[2].trim().split(",");
                if (coords.length == 4) {
                    try {
                        el.setBoundingBox(new BoundingBox(
                            Double.parseDouble(coords[0].trim()),
                            Double.parseDouble(coords[1].trim()),
                            Double.parseDouble(coords[2].trim()),
                            Double.parseDouble(coords[3].trim())
                        ));
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (el.isConfidentEnough(minConfidence)) {
                elements.add(el);
            }
        }
        log.debug("Vision parsed {} elements from NIM response", elements.size());
        return elements;
    }
}
