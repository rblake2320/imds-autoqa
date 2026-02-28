package autoqa.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible HTTP client that targets Ollama's /v1/chat/completions endpoint.
 * Supports configurable timeouts and automatic retry on 5xx errors.
 */
public class LLMClient {

    private static final Logger log = LoggerFactory.getLogger(LLMClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final int timeoutSec;
    private final int retryCount;
    private final long retryDelayMs;
    private final OkHttpClient httpClient;

    public LLMClient(String baseUrl, String model, double temperature, int maxTokens,
                     int timeoutSec, int retryCount, long retryDelayMs) {
        this.baseUrl      = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model        = model;
        this.temperature  = temperature;
        this.maxTokens    = maxTokens;
        this.timeoutSec   = timeoutSec;
        this.retryCount   = retryCount;
        this.retryDelayMs = retryDelayMs;
        this.httpClient   = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Sends a chat completion request to the configured Ollama endpoint.
     *
     * @param messages list of {@link ChatMessage} objects in conversation order
     * @return the assistant's response text (choices[0].message.content), trimmed
     * @throws IOException if the request fails after all retries are exhausted
     */
    public String complete(List<ChatMessage> messages) throws IOException {
        // Log first user message snippet at DEBUG for diagnostics
        messages.stream()
                .filter(m -> "user".equals(m.role()))
                .findFirst()
                .ifPresent(m -> {
                    String snippet = m.content().length() > 500
                            ? m.content().substring(0, 500) + "..."
                            : m.content();
                    log.debug("LLM request to {} | model={} | user_prompt_start={}",
                            baseUrl, model, snippet);
                });

        String requestJson = buildRequestJson(messages);
        String url = baseUrl + "/chat/completions";

        IOException lastException = null;

        for (int attempt = 0; attempt <= retryCount; attempt++) {
            if (attempt > 0) {
                log.warn("Retrying LLM request (attempt {}/{}) after {}ms delay",
                        attempt, retryCount, retryDelayMs);
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted during retry delay", ie);
                }
            }

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(requestJson, JSON))
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                int statusCode = response.code();

                if (statusCode >= 500) {
                    String errorBody = response.body() != null ? response.body().string() : "<empty>";
                    log.warn("LLM endpoint returned {} on attempt {}: {}", statusCode, attempt + 1, errorBody);
                    lastException = new IOException(
                            "LLM server error " + statusCode + " after " + (attempt + 1) + " attempt(s): " + errorBody);
                    continue; // retry
                }

                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "<empty>";
                    throw new IOException("LLM request failed with HTTP " + statusCode + ": " + errorBody);
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                return parseContent(responseBody);

            } catch (IOException e) {
                if (e.getMessage() != null && (
                        e.getMessage().startsWith("LLM request failed with HTTP") ||
                        e.getMessage().startsWith("LLM response missing") ||
                        e.getMessage().startsWith("Failed to parse LLM response"))) {
                    throw e; // non-retriable: HTTP errors, parse errors, malformed responses
                }
                log.warn("LLM request I/O error on attempt {}: {}", attempt + 1, e.getMessage());
                lastException = e;
            }
        }

        throw new IOException(
                "LLM request failed after " + (retryCount + 1) + " attempt(s). Last error: "
                        + (lastException != null ? lastException.getMessage() : "unknown"),
                lastException);
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Builds the JSON request body for the /chat/completions endpoint.
     */
    private String buildRequestJson(List<ChatMessage> messages) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("temperature", temperature);
        root.put("max_tokens", maxTokens);
        root.put("stream", false);  // Ollama defaults to streaming — disable it explicitly

        ArrayNode msgs = root.putArray("messages");
        for (ChatMessage msg : messages) {
            ObjectNode msgNode = msgs.addObject();
            msgNode.put("role", msg.role());
            msgNode.put("content", msg.content());
        }

        return MAPPER.writeValueAsString(root);
    }

    /**
     * Extracts the assistant content string from the choices[0].message.content path.
     */
    private String parseContent(String responseBody) throws IOException {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IOException("LLM response missing 'choices' array: " + responseBody);
            }
            JsonNode content = choices.get(0).path("message").path("content");
            if (content.isMissingNode()) {
                throw new IOException("LLM response missing choices[0].message.content: " + responseBody);
            }
            return content.asText().trim();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IOException("Failed to parse LLM response JSON: " + e.getMessage(), e);
        }
    }

    // ── Nested types ──────────────────────────────────────────────────────

    /**
     * Simple record representing a single chat turn.
     */
    public record ChatMessage(String role, String content) {

        /** Creates a system-role message. */
        public static ChatMessage system(String content) {
            return new ChatMessage("system", content);
        }

        /** Creates a user-role message. */
        public static ChatMessage user(String content) {
            return new ChatMessage("user", content);
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────

    /**
     * Creates an {@link LLMClient} from {@code config.properties} on the classpath.
     *
     * <p>Recognised keys and their defaults:
     * <ul>
     *   <li>{@code ai.llm.base.url}       — {@code http://localhost:11434/v1}</li>
     *   <li>{@code ai.llm.model}           — {@code qwen2.5-coder:32b}</li>
     *   <li>{@code ai.llm.temperature}     — {@code 0.1}</li>
     *   <li>{@code ai.llm.max.tokens}      — {@code 4096}</li>
     *   <li>{@code ai.llm.timeout.sec}     — {@code 120}</li>
     *   <li>{@code ai.llm.retry.count}     — {@code 2}</li>
     *   <li>{@code ai.llm.retry.delay.ms}  — {@code 2000}</li>
     * </ul>
     *
     * @return a fully configured {@link LLMClient}
     * @throws IllegalStateException if {@code config.properties} cannot be loaded
     */
    public static LLMClient fromConfig() {
        Properties props = new Properties();
        try (InputStream is = LLMClient.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (is != null) {
                props.load(is);
                log.debug("Loaded config.properties from classpath");
            } else {
                log.warn("config.properties not found on classpath — using all defaults");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read config.properties: " + e.getMessage(), e);
        }

        String baseUrl      = props.getProperty("ai.llm.base.url",      "http://localhost:11434/v1");
        String model        = props.getProperty("ai.llm.model",          "qwen2.5-coder:32b");
        double temperature  = parseDouble(props, "ai.llm.temperature",    0.1);
        int    maxTokens    = parseInt(props,    "ai.llm.max.tokens",     4096);
        int    timeoutSec   = parseInt(props,    "ai.llm.timeout.sec",    120);
        int    retryCount   = parseInt(props,    "ai.llm.retry.count",    2);
        long   retryDelayMs = parseLong(props,   "ai.llm.retry.delay.ms", 2000L);

        log.info("LLMClient configured: baseUrl={}, model={}, temperature={}, maxTokens={}, "
                        + "timeoutSec={}, retryCount={}, retryDelayMs={}",
                baseUrl, model, temperature, maxTokens, timeoutSec, retryCount, retryDelayMs);

        return new LLMClient(baseUrl, model, temperature, maxTokens, timeoutSec, retryCount, retryDelayMs);
    }

    private static double parseDouble(Properties props, String key, double defaultVal) {
        String val = props.getProperty(key);
        if (val == null || val.isBlank()) return defaultVal;
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid value for '{}': '{}' — using default {}", key, val, defaultVal);
            return defaultVal;
        }
    }

    private static int parseInt(Properties props, String key, int defaultVal) {
        String val = props.getProperty(key);
        if (val == null || val.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid value for '{}': '{}' — using default {}", key, val, defaultVal);
            return defaultVal;
        }
    }

    private static long parseLong(Properties props, String key, long defaultVal) {
        String val = props.getProperty(key);
        if (val == null || val.isBlank()) return defaultVal;
        try {
            return Long.parseLong(val.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid value for '{}': '{}' — using default {}", key, val, defaultVal);
            return defaultVal;
        }
    }
}
