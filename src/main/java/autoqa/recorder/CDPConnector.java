package autoqa.recorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * WebSocket-based connection to Edge DevTools Protocol.
 *
 * <p>Connects to {@code http://localhost:{port}/json} to discover the
 * debugger WebSocket URL, then opens a persistent WebSocket to
 * {@code ws://localhost:{port}/devtools/page/{targetId}}.
 *
 * <p>Outbound CDP commands are sent as JSON objects with an integer {@code id}.
 * Responses bearing an {@code id} field are resolved to their corresponding
 * {@link CompletableFuture}; objects without {@code id} (i.e. events) are
 * dispatched to all registered event listeners.
 */
public class CDPConnector implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CDPConnector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Maximum length of a CDP message logged at DEBUG level. */
    private static final int LOG_TRUNCATE_LEN = 200;

    /** How long to wait for a CDP command response before giving up. */
    private static final int COMMAND_TIMEOUT_SEC = 30;

    /** Number of connection attempts before surfacing an error. */
    private static final int MAX_CONNECT_RETRIES = 3;

    /** Delay between connection retries in milliseconds. */
    private static final long RETRY_DELAY_MS = 1_000L;

    private final int port;
    private final int connectTimeoutSec;
    private final OkHttpClient httpClient;

    private WebSocket webSocket;
    private final AtomicInteger commandId = new AtomicInteger(1);

    /** Futures awaiting a CDP command response, keyed by command id. */
    private final Map<Integer, CompletableFuture<JsonNode>> pendingCommands =
            new ConcurrentHashMap<>();

    /** All registered event listeners. CopyOnWrite for safe concurrent iteration. */
    private final List<Consumer<JsonNode>> eventListeners = new CopyOnWriteArrayList<>();

    // ── Construction ──────────────────────────────────────────────────────

    /**
     * Creates a connector that will attach to Edge on the given debug port.
     *
     * @param port             Edge remote debugging port (typically 9222)
     * @param connectTimeoutSec TCP connect / HTTP timeout in seconds
     */
    public CDPConnector(int port, int connectTimeoutSec) {
        this.port = port;
        this.connectTimeoutSec = connectTimeoutSec;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
                .readTimeout(connectTimeoutSec, TimeUnit.SECONDS)
                .writeTimeout(connectTimeoutSec, TimeUnit.SECONDS)
                .build();
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Connects to Edge on the configured port.
     *
     * <p>Fetches {@code http://localhost:{port}/json}, finds the first entry
     * whose {@code type} equals {@code "page"}, extracts its
     * {@code webSocketDebuggerUrl}, and opens a WebSocket to that URL.
     *
     * <p>Retries up to {@value #MAX_CONNECT_RETRIES} times with a
     * {@value #RETRY_DELAY_MS} ms delay between attempts.
     *
     * @throws IOException if all retries fail
     */
    public void connect() throws IOException {
        IOException lastError = null;

        for (int attempt = 1; attempt <= MAX_CONNECT_RETRIES; attempt++) {
            try {
                String wsUrl = discoverDebuggerUrl();
                openWebSocket(wsUrl);
                log.info("Connected to Edge CDP on port {} (attempt {})", port, attempt);
                return;
            } catch (IOException e) {
                lastError = e;
                log.warn("CDP connect attempt {}/{} failed: {}", attempt, MAX_CONNECT_RETRIES,
                        e.getMessage());
                if (attempt < MAX_CONNECT_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting to retry CDP connect", ie);
                    }
                }
            }
        }

        throw new IOException(
                "Failed to connect to Edge CDP on port " + port
                + " after " + MAX_CONNECT_RETRIES + " attempts",
                lastError);
    }

    /**
     * Sends a CDP command and blocks until the response arrives or the
     * {@value #COMMAND_TIMEOUT_SEC}-second timeout expires.
     *
     * @param method CDP method name, e.g. {@code "Runtime.evaluate"}
     * @param params method parameters (may be an empty ObjectNode)
     * @return the {@code "result"} field of the CDP response
     * @throws IOException if the command cannot be sent, the response contains
     *                     an {@code error} field, or the wait times out
     */
    public JsonNode sendCommand(String method, ObjectNode params) throws IOException {
        int id = commandId.getAndIncrement();

        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("id", id);
        envelope.put("method", method);
        envelope.set("params", params != null ? params : MAPPER.createObjectNode());

        String json = MAPPER.writeValueAsString(envelope);
        log.debug("CDP >> [{}] {}", id, truncate(json));

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingCommands.put(id, future);

        if (!webSocket.send(json)) {
            pendingCommands.remove(id);
            throw new IOException("WebSocket.send() returned false for command id " + id);
        }

        try {
            JsonNode response = future.get(COMMAND_TIMEOUT_SEC, TimeUnit.SECONDS);

            if (response.has("error")) {
                JsonNode err = response.get("error");
                throw new IOException("CDP error for " + method + ": " + err);
            }

            JsonNode result = response.get("result");
            log.debug("CDP << [{}] {}", id,
                    truncate(result == null ? "null" : result.toString()));
            return result;

        } catch (TimeoutException e) {
            pendingCommands.remove(id);
            throw new IOException("CDP command timed out after " + COMMAND_TIMEOUT_SEC
                    + "s: " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pendingCommands.remove(id);
            throw new IOException("Interrupted waiting for CDP response: " + method, e);
        } catch (ExecutionException e) {
            throw new IOException("CDP command execution failed: " + method, e.getCause());
        }
    }

    /**
     * Registers a listener that is called for every CDP event (messages that
     * have a {@code "method"} field but no {@code "id"} field).
     *
     * <p>The listener receives the full event JsonNode including
     * {@code "method"} and {@code "params"}.
     *
     * @param listener event consumer; called on the WebSocket reader thread
     */
    public void addEventListeners(Consumer<JsonNode> listener) {
        eventListeners.add(listener);
    }

    /**
     * Enables a CDP domain by sending a {@code {domain}.enable} command with
     * empty parameters.
     *
     * @param domain CDP domain name, e.g. {@code "Network"}, {@code "Page"}, {@code "DOM"}
     * @throws IOException if the enable command fails
     */
    public void enable(String domain) throws IOException {
        log.debug("Enabling CDP domain: {}", domain);
        sendCommand(domain + ".enable", MAPPER.createObjectNode());
    }

    @Override
    public void close() {
        if (webSocket != null) {
            webSocket.close(1000, "Recording stopped");
            log.debug("CDP WebSocket closed");
        }
        httpClient.dispatcher().executorService().shutdown();
        // Cancel any still-pending futures so callers do not hang indefinitely
        pendingCommands.values().forEach(f ->
                f.completeExceptionally(new IOException("CDPConnector closed")));
        pendingCommands.clear();
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * GETs {@code http://localhost:{port}/json} and extracts the
     * {@code webSocketDebuggerUrl} for the first {@code "page"} target.
     */
    private String discoverDebuggerUrl() throws IOException {
        String url = "http://localhost:" + port + "/json";
        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " from " + url);
            }
            String body = response.body() != null ? response.body().string() : "";
            JsonNode targets = MAPPER.readTree(body);

            if (!targets.isArray()) {
                throw new IOException("/json did not return a JSON array");
            }

            for (JsonNode target : targets) {
                JsonNode typeNode = target.get("type");
                if (typeNode != null && "page".equals(typeNode.asText())) {
                    JsonNode wsUrl = target.get("webSocketDebuggerUrl");
                    if (wsUrl != null && !wsUrl.asText().isBlank()) {
                        log.debug("Discovered CDP WebSocket URL: {}", wsUrl.asText());
                        return wsUrl.asText();
                    }
                }
            }

            throw new IOException("No 'page' target with webSocketDebuggerUrl found in " + url
                    + ". Response: " + truncate(body));
        }
    }

    /** Opens a WebSocket to the given URL and waits for the OPEN callback. */
    private void openWebSocket(String wsUrl) throws IOException {
        CountDownLatch openLatch = new CountDownLatch(1);
        IOException[] openError = {null};

        Request request = new Request.Builder().url(wsUrl).build();
        webSocket = httpClient.newWebSocket(request, new CDPWebSocketListener(openLatch, openError));

        try {
            boolean opened = openLatch.await(connectTimeoutSec, TimeUnit.SECONDS);
            if (!opened) {
                throw new IOException("WebSocket did not open within " + connectTimeoutSec + "s");
            }
            if (openError[0] != null) {
                throw openError[0];
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for WebSocket to open", e);
        }
    }

    private static String truncate(String s) {
        if (s == null) return "null";
        return s.length() <= LOG_TRUNCATE_LEN ? s : s.substring(0, LOG_TRUNCATE_LEN) + "…";
    }

    // ── WebSocket listener ────────────────────────────────────────────────

    /**
     * Inner listener that routes incoming WebSocket messages:
     * <ul>
     *   <li>Messages with an {@code id} field complete the matching pending future.</li>
     *   <li>Messages without an {@code id} (CDP events) are dispatched to all
     *       registered {@link #eventListeners}.</li>
     * </ul>
     */
    private class CDPWebSocketListener extends WebSocketListener {

        private final CountDownLatch openLatch;
        private final IOException[] openError;

        CDPWebSocketListener(CountDownLatch openLatch, IOException[] openError) {
            this.openLatch = openLatch;
            this.openError = openError;
        }

        @Override
        public void onOpen(WebSocket ws, Response response) {
            log.debug("CDP WebSocket opened");
            openLatch.countDown();
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            log.debug("CDP << raw: {}", truncate(text));
            try {
                JsonNode node = MAPPER.readTree(text);

                JsonNode idNode = node.get("id");
                if (idNode != null && !idNode.isNull()) {
                    int id = idNode.asInt();
                    CompletableFuture<JsonNode> future = pendingCommands.remove(id);
                    if (future != null) {
                        future.complete(node);
                    } else {
                        log.warn("Received response for unknown command id {}", id);
                    }
                } else {
                    // CDP event — no id field
                    for (Consumer<JsonNode> listener : eventListeners) {
                        try {
                            listener.accept(node);
                        } catch (Exception ex) {
                            log.warn("CDP event listener threw: {}", ex.getMessage(), ex);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Failed to parse CDP message: {}", truncate(text), e);
            }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            log.error("CDP WebSocket failure: {}", t.getMessage(), t);
            // If we're still waiting for the open latch, surface the error
            if (openLatch.getCount() > 0) {
                openError[0] = new IOException("WebSocket connection failed: " + t.getMessage(), t);
                openLatch.countDown();
            }
            // Fail all pending command futures
            IOException err = new IOException("WebSocket failure: " + t.getMessage(), t);
            pendingCommands.values().forEach(f -> f.completeExceptionally(err));
            pendingCommands.clear();
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            log.debug("CDP WebSocket closed: code={}, reason={}", code, reason);
        }
    }
}
