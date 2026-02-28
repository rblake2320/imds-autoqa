package autoqa.ai;

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
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * W&B Weave trace client — logs test execution spans to Weights &amp; Biases (W&amp;B)
 * for observability, debugging, and AI-powered test analysis.
 *
 * <p>Inspired by the wandb/ai-virtual-assistant blueprint, this class provides
 * a lightweight Java bridge to the W&amp;B Weave Calls API, enabling:
 * <ul>
 *   <li>Test run tracing (each test = one trace/span)</li>
 *   <li>Step-level spans with inputs/outputs/timing</li>
 *   <li>LLM call logging (healing, generation, failure analysis)</li>
 *   <li>Visual dashboard of test execution history</li>
 * </ul>
 *
 * <p>Enable by setting {@code wandb.enabled=true} and {@code wandb.api.key=...} in
 * {@code config.properties}, or set {@code WANDB_API_KEY} environment variable.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * WandbTraceClient tracer = WandbTraceClient.fromConfig();
 * String traceId = tracer.startTrace("login_test", "Test", Map.of("url", "https://example.com"));
 * // ... run test ...
 * tracer.endTrace(traceId, Map.of("steps_completed", 5), null);
 * tracer.flush();
 * }</pre>
 *
 * <p>When W&amp;B is not configured, all operations are no-ops (safe to use in all environments).
 */
public class WandbTraceClient {

    private static final Logger log = LoggerFactory.getLogger(WandbTraceClient.class);
    private static final MediaType JSON_MT = MediaType.get("application/json; charset=utf-8");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String WEAVE_BASE = "https://trace.wandb.ai";

    private final boolean enabled;
    private final String  apiKey;
    private final String  project;     // e.g. "myorg/imds-autoqa"
    private final OkHttpClient http;

    // ── Factory ───────────────────────────────────────────────────────────────

    /** Creates a no-op client (safe when W&B is not configured). */
    public static WandbTraceClient disabled() {
        return new WandbTraceClient(false, null, null);
    }

    /** Creates a client from config.properties / environment variables. */
    public static WandbTraceClient fromConfig() {
        try (var is = WandbTraceClient.class.getResourceAsStream("/config.properties")) {
            var props = new java.util.Properties();
            if (is != null) props.load(is);

            boolean enabled = Boolean.parseBoolean(props.getProperty("wandb.enabled", "false").trim());
            if (!enabled) return disabled();

            String envKey = props.getProperty("wandb.api.key.env", "WANDB_API_KEY");
            String apiKey = System.getenv(envKey);
            if (apiKey == null) apiKey = props.getProperty("wandb.api.key", "");
            String project = props.getProperty("wandb.project", "autoqa/imds-autoqa");
            return new WandbTraceClient(true, apiKey, project);
        } catch (IOException e) {
            return disabled();
        }
    }

    /** Creates a configured client. */
    public static WandbTraceClient of(String apiKey, String project) {
        return new WandbTraceClient(true, apiKey, project);
    }

    private WandbTraceClient(boolean enabled, String apiKey, String project) {
        this.enabled = enabled;
        this.apiKey  = apiKey;
        this.project = project;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30,  TimeUnit.SECONDS)
                .build();
    }

    // ── Trace API ─────────────────────────────────────────────────────────────

    /**
     * Starts a new trace (root span) for a test run.
     *
     * @param name    display name (e.g. test method name)
     * @param opName  operation type (e.g. "Test", "Playback", "Heal")
     * @param inputs  key-value inputs to log (e.g. recording file, URL)
     * @return trace ID for use with {@link #endTrace}; may be {@code null} if disabled
     */
    public String startTrace(String name, String opName, java.util.Map<String, Object> inputs) {
        if (!enabled) return null;
        String id = java.util.UUID.randomUUID().toString();
        sendCallStart(id, null, name, opName, inputs);
        return id;
    }

    /**
     * Ends a trace, recording outputs and optional exception.
     *
     * @param traceId  the ID returned by {@link #startTrace}
     * @param outputs  key-value outputs (e.g. steps_completed, passed)
     * @param error    exception if the test failed, or {@code null}
     */
    public void endTrace(String traceId, java.util.Map<String, Object> outputs, Throwable error) {
        if (!enabled || traceId == null) return;
        sendCallEnd(traceId, outputs, error);
    }

    /**
     * Logs a child span inside a parent trace (e.g. a single test step, an LLM call).
     *
     * @param parentId  parent trace ID
     * @param name      span name (e.g. "click:loginButton")
     * @param opName    operation type (e.g. "Step", "LLM.complete")
     * @param inputs    inputs to log
     * @param outputs   outputs to log (or null)
     * @param durationMs duration in milliseconds
     * @param error     exception if the span failed, or null
     */
    public void logSpan(String parentId, String name, String opName,
                         java.util.Map<String, Object> inputs,
                         java.util.Map<String, Object> outputs,
                         long durationMs, Throwable error) {
        if (!enabled || parentId == null) return;
        String spanId = java.util.UUID.randomUUID().toString();
        sendCallStart(spanId, parentId, name, opName, inputs);
        sendCallEnd(spanId, outputs, error);
    }

    // ── HTTP calls ────────────────────────────────────────────────────────────

    private void sendCallStart(String id, String parentId, String name, String opName,
                                 java.util.Map<String, Object> inputs) {
        try {
            ObjectNode call = MAPPER.createObjectNode();
            call.put("id",         id);
            call.put("op_name",    opName != null ? opName : name);
            call.put("display_name", name);
            call.put("started_at", Instant.now().toString());
            call.put("project_id", project);
            if (parentId != null) call.put("parent_id", parentId);
            if (inputs != null) call.set("inputs", MAPPER.valueToTree(inputs));

            ObjectNode body = MAPPER.createObjectNode();
            ArrayNode calls = MAPPER.createArrayNode();
            calls.add(call);
            body.set("calls", calls);

            post(WEAVE_BASE + "/call/start", body.toString());
        } catch (Exception e) {
            log.debug("W&B trace start failed (non-critical): {}", e.getMessage());
        }
    }

    private void sendCallEnd(String id, java.util.Map<String, Object> outputs, Throwable error) {
        try {
            ObjectNode call = MAPPER.createObjectNode();
            call.put("id",        id);
            call.put("ended_at",  Instant.now().toString());
            if (outputs != null) call.set("output", MAPPER.valueToTree(outputs));
            if (error   != null) call.put("exception", error.getClass().getSimpleName() + ": " + error.getMessage());

            ObjectNode body = MAPPER.createObjectNode();
            ArrayNode calls = MAPPER.createArrayNode();
            calls.add(call);
            body.set("calls", calls);

            post(WEAVE_BASE + "/call/end", body.toString());
        } catch (Exception e) {
            log.debug("W&B trace end failed (non-critical): {}", e.getMessage());
        }
    }

    private void post(String url, String json) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(json, JSON_MT))
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.debug("W&B API returned {}: {}", response.code(),
                        response.body() != null ? response.body().string() : "");
            }
        }
    }

    /** No-op (kept for API clarity). */
    public void flush() {}

    public boolean isEnabled() { return enabled; }
}
