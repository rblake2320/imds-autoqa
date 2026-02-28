package autoqa.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP API server for IMDS AutoQA IDE integration.
 *
 * <p>Listens on localhost:{port} (default 8765) and exposes REST endpoints
 * for recording, playback, test generation, and healing operations.
 * Uses JDK's built-in {@code com.sun.net.httpserver.HttpServer} — zero extra dependencies.
 *
 * <p>All responses include CORS headers so the HTML IDE at file:// can call it.
 */
@SuppressWarnings("restriction")
public class APIServer {

    // ── Server state ─────────────────────────────────────────────────────────

    public enum ServerState {
        idle, recording, playing, generating, healing
    }

    private static volatile ServerState currentState = ServerState.idle;
    private static volatile String currentRecordingPath = null;
    private static volatile String lastRecording = null;
    private static volatile Object lastResult = null;

    // ── Log buffer ───────────────────────────────────────────────────────────

    private static final int MAX_LOG_LINES = 500;
    private static final LinkedList<String> logBuffer = new LinkedList<>();

    private static synchronized void appendLog(String line) {
        logBuffer.add(line);
        while (logBuffer.size() > MAX_LOG_LINES) {
            logBuffer.removeFirst();
        }
    }

    private static synchronized List<String> getLastLog(int n) {
        int size = logBuffer.size();
        int from = Math.max(0, size - n);
        return List.copyOf(logBuffer.subList(from, size));
    }

    // ── Background process ───────────────────────────────────────────────────

    private static volatile Process bgProcess = null;

    // ── Core fields ──────────────────────────────────────────────────────────

    private final int port;
    private HttpServer httpServer;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String RECORDINGS_DIR = "D:/imds-autoqa/recordings";
    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // ── Constructor ──────────────────────────────────────────────────────────

    public APIServer(int port) {
        this.port = port;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    public void start() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            httpServer.setExecutor(Executors.newFixedThreadPool(4));

            // Register all endpoints
            httpServer.createContext("/api/status",         this::handleStatus);
            httpServer.createContext("/api/recordings",     this::handleRecordingsList);
            httpServer.createContext("/api/record/start",   this::handleRecordStart);
            httpServer.createContext("/api/record/stop",    this::handleRecordStop);
            httpServer.createContext("/api/play",           this::handlePlay);
            httpServer.createContext("/api/generate",       this::handleGenerate);
            httpServer.createContext("/api/heal",           this::handleHeal);
            httpServer.createContext("/api/stop",           this::handleStop);
            httpServer.createContext("/api/log",            this::handleLog);
            // recording content: /api/recordings/{name}/content
            httpServer.createContext("/api/recordings/",    this::handleRecordingContent);

            httpServer.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start API server on port " + port, e);
        }
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(1);
        }
        killBgProcess();
    }

    // ── Handlers ─────────────────────────────────────────────────────────────

    /** GET /api/status */
    private void handleStatus(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!assertMethod(exchange, "GET")) return;

        ObjectNode body = mapper.createObjectNode();
        body.put("state", currentState.name());
        if (currentRecordingPath != null) {
            body.put("recording", currentRecordingPath);
        } else {
            body.putNull("recording");
        }
        if (lastRecording != null) {
            body.put("lastRecording", lastRecording);
        } else {
            body.putNull("lastRecording");
        }
        if (lastResult != null) {
            body.putPOJO("lastResult", lastResult);
        } else {
            body.putNull("lastResult");
        }
        sendJson(exchange, 200, body);
    }

    /** GET /api/recordings */
    private void handleRecordingsList(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!assertMethod(exchange, "GET")) return;

        ArrayNode arr = mapper.createArrayNode();
        Path dir = Paths.get(RECORDINGS_DIR);

        if (Files.exists(dir) && Files.isDirectory(dir)) {
            try {
                Files.list(dir)
                        .filter(p -> p.toString().endsWith(".json"))
                        .sorted(Comparator.comparingLong(p -> {
                            try {
                                return -Files.getLastModifiedTime(p).toMillis();
                            } catch (IOException e) {
                                return 0L;
                            }
                        }))
                        .forEach(p -> {
                            try {
                                BasicFileAttributes attrs =
                                        Files.readAttributes(p, BasicFileAttributes.class);
                                ObjectNode entry = mapper.createObjectNode();
                                entry.put("name", p.getFileName().toString());
                                entry.put("path", p.toString().replace('\\', '/'));
                                entry.put("size", attrs.size());
                                LocalDateTime modified = LocalDateTime.ofInstant(
                                        attrs.lastModifiedTime().toInstant(),
                                        ZoneId.systemDefault());
                                entry.put("modified", modified.format(ISO_FMT));
                                arr.add(entry);
                            } catch (IOException ignored) { /* skip bad entries */ }
                        });
            } catch (IOException e) {
                appendLog("Error listing recordings: " + e.getMessage());
            }
        }

        sendJson(exchange, 200, arr);
    }

    /** POST /api/record/start — body: {"url":"https://...","name":"my-flow"} */
    private void handleRecordStart(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!assertMethod(exchange, "POST")) return;

        Map<?, ?> body = readJsonBody(exchange);
        if (body == null) return;

        String url  = (String) body.get("url");
        String name = body.get("name") != null ? (String) body.get("name") : "recording-" + Instant.now().getEpochSecond();

        if (url == null || url.isBlank()) {
            sendError(exchange, 400, "Missing required field: url");
            return;
        }

        String jarPath = resolveJarPath();
        currentState = ServerState.recording;
        currentRecordingPath = RECORDINGS_DIR + "/" + name + ".json";

        runBackground(resolveJavaExe(), "-jar", jarPath, "record", "start", "--url", url, "--name", name);

        ObjectNode resp = mapper.createObjectNode();
        resp.put("ok", true);
        resp.put("state", "recording");
        resp.put("message", "Recording started");
        sendJson(exchange, 200, resp);
    }

    /** POST /api/record/stop */
    private void handleRecordStop(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!assertMethod(exchange, "POST")) return;

        String jarPath = resolveJarPath();
        String recordingPath = currentRecordingPath;

        // Delete sentinel lock file so the recorder's watcher thread triggers graceful save
        try {
            java.nio.file.Files.deleteIfExists(
                    java.nio.file.Paths.get(RECORDINGS_DIR, ".autoqa-recording.lock"));
        } catch (Exception ignored) { /* non-fatal */ }

        killBgProcess();
        runBackground(resolveJavaExe(), "-jar", jarPath, "record", "stop");

        lastRecording = recordingPath;
        currentState = ServerState.idle;

        ObjectNode resp = mapper.createObjectNode();
        resp.put("ok", true);
        resp.put("state", "idle");
        if (recordingPath != null) {
            resp.put("path", recordingPath);
        } else {
            resp.putNull("path");
        }
        sendJson(exchange, 200, resp);
    }

    /** POST /api/play — body: {"recording":"...","browser":"edge"} */
    private void handlePlay(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!assertMethod(exchange, "POST")) return;

        Map<?, ?> body = readJsonBody(exchange);
        if (body == null) return;

        String recording = (String) body.get("recording");
        if (recording == null || recording.isBlank()) {
            sendError(exchange, 400, "Missing required field: recording");
            return;
        }

        String jarPath = resolveJarPath();
        currentState = ServerState.playing;

        runBackground(resolveJavaExe(), "-jar", jarPath, "play", recording);

        ObjectNode resp = mapper.createObjectNode();
        resp.put("ok", true);
        resp.put("state", "playing");
        resp.put("message", "Playback started");
        sendJson(exchange, 200, resp);
    }

    /** POST /api/generate — body: {"recording":"..."} */
    private void handleGenerate(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!assertMethod(exchange, "POST")) return;

        Map<?, ?> body = readJsonBody(exchange);
        if (body == null) return;

        String recording = (String) body.get("recording");
        if (recording == null || recording.isBlank()) {
            sendError(exchange, 400, "Missing required field: recording");
            return;
        }

        String jarPath = resolveJarPath();
        currentState = ServerState.generating;

        runBackground(resolveJavaExe(), "-jar", jarPath, "generate", recording);

        ObjectNode resp = mapper.createObjectNode();
        resp.put("ok", true);
        resp.put("state", "generating");
        resp.put("message", "Test generation started");
        sendJson(exchange, 200, resp);
    }

    /** POST /api/heal — body: {"recording":"..."} */
    private void handleHeal(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!assertMethod(exchange, "POST")) return;

        Map<?, ?> body = readJsonBody(exchange);
        if (body == null) return;

        String recording = (String) body.get("recording");
        if (recording == null || recording.isBlank()) {
            sendError(exchange, 400, "Missing required field: recording");
            return;
        }

        String jarPath = resolveJarPath();
        currentState = ServerState.healing;

        runBackground(resolveJavaExe(), "-jar", jarPath, "heal", recording);

        ObjectNode resp = mapper.createObjectNode();
        resp.put("ok", true);
        resp.put("state", "healing");
        resp.put("message", "Healing started");
        sendJson(exchange, 200, resp);
    }

    /** POST /api/stop — kills any running background process */
    private void handleStop(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!assertMethod(exchange, "POST")) return;

        killBgProcess();
        currentState = ServerState.idle;

        ObjectNode resp = mapper.createObjectNode();
        resp.put("ok", true);
        resp.put("state", "idle");
        sendJson(exchange, 200, resp);
    }

    /** GET /api/log — returns last 200 lines of process output */
    private void handleLog(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!assertMethod(exchange, "GET")) return;

        List<String> lines = getLastLog(200);
        sendJson(exchange, 200, lines);
    }

    /** GET /api/recordings/{name}/content — returns raw JSON of a recording file */
    private void handleRecordingContent(HttpExchange exchange) throws IOException {
        if (handleCors(exchange)) return;
        if (!assertMethod(exchange, "GET")) return;

        String path = exchange.getRequestURI().getPath();
        // path: /api/recordings/{name}/content
        // strip leading /api/recordings/ and trailing /content
        String stripped = path.replaceFirst("^/api/recordings/", "").replaceFirst("/content$", "");
        if (stripped.isBlank()) {
            // No subpath — delegate to list handler
            handleRecordingsList(exchange);
            return;
        }

        // Only handle paths that end with /content
        if (!path.endsWith("/content")) {
            sendError(exchange, 404, "Not found: " + path);
            return;
        }

        Path file = Paths.get(RECORDINGS_DIR, stripped);
        if (!Files.exists(file)) {
            sendError(exchange, 404, "Recording not found: " + stripped);
            return;
        }

        byte[] content = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        setCorsHeaders(exchange);
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the JAR path to use for sub-process invocations.
     * Prefers the built target JAR; falls back to the currently running JAR.
     */
    private String resolveJarPath() {
        File primary = new File("D:/imds-autoqa/target/autoqa.jar");
        if (primary.exists()) {
            return primary.getAbsolutePath();
        }
        // Fall back to the location of the currently running JAR
        try {
            String codeSource = APIServer.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
                    .getPath();
            if (codeSource != null && codeSource.endsWith(".jar")) {
                return codeSource;
            }
        } catch (Exception ignored) { /* use fallback */ }
        // Last resort: assume standard build output path
        return "D:/imds-autoqa/target/autoqa.jar";
    }

    /**
     * Resolves the java executable path.
     * Uses JAVA_HOME if set, otherwise assumes 'java' is on PATH.
     */
    private String resolveJavaExe() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            File javaExe = new File(javaHome, "bin/java");
            if (javaExe.exists()) return javaExe.getAbsolutePath();
            File javaExeWin = new File(javaHome, "bin/java.exe");
            if (javaExeWin.exists()) return javaExeWin.getAbsolutePath();
        }
        return "java";
    }

    /**
     * Starts a background process, streaming its combined output into the log buffer.
     * Only one background process can run at a time; any existing one is killed first.
     */
    private void runBackground(String... cmd) {
        killBgProcess();
        appendLog("$ " + String.join(" ", cmd));

        Thread runner = new Thread(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true); // merge stderr → stdout
                bgProcess = pb.start();

                Process proc = bgProcess;
                // Stream output to log buffer
                Thread reader = new Thread(() -> {
                    try (InputStream is = proc.getInputStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        StringBuilder line = new StringBuilder();
                        while ((n = is.read(buf)) != -1) {
                            String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                            for (char c : chunk.toCharArray()) {
                                if (c == '\n') {
                                    appendLog(line.toString());
                                    line.setLength(0);
                                } else if (c != '\r') {
                                    line.append(c);
                                }
                            }
                        }
                        if (line.length() > 0) {
                            appendLog(line.toString());
                        }
                    } catch (IOException e) {
                        appendLog("[reader error] " + e.getMessage());
                    }
                }, "api-log-reader");
                reader.setDaemon(true);
                reader.start();

                int exitCode = proc.waitFor();
                reader.join(3000);
                appendLog("[process exited with code " + exitCode + "]");

                // Reset state to idle when process finishes (unless already changed)
                if (currentState != ServerState.idle) {
                    currentState = ServerState.idle;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                appendLog("[process interrupted]");
            } catch (IOException e) {
                appendLog("[process launch error] " + e.getMessage());
                currentState = ServerState.idle;
            }
        }, "api-bg-process");
        runner.setDaemon(true);
        runner.start();
    }

    /** Kills the current background process if one is running. */
    private void killBgProcess() {
        Process proc = bgProcess;
        if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
            appendLog("[process killed]");
        }
        bgProcess = null;
    }

    /**
     * Sends CORS pre-flight OPTIONS response.
     * Returns true if this was an OPTIONS request (caller should return immediately).
     */
    private boolean handleCors(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return true;
        }
        return false;
    }

    /** Adds CORS headers to every response. */
    private void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    /**
     * Checks that the request uses the expected HTTP method.
     * Returns true if ok; sends 405 and returns false otherwise.
     */
    private boolean assertMethod(HttpExchange exchange, String expected) throws IOException {
        if (!expected.equalsIgnoreCase(exchange.getRequestMethod())) {
            sendError(exchange, 405, "Method Not Allowed — expected " + expected);
            return false;
        }
        return true;
    }

    /**
     * Reads and parses the request body as a JSON object.
     * Returns null and sends a 400 error if the body is empty or invalid JSON.
     */
    @SuppressWarnings("unchecked")
    private Map<?, ?> readJsonBody(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        if (raw.length == 0) {
            // Allow empty body — treat as empty map
            return Map.of();
        }
        try {
            return mapper.readValue(raw, Map.class);
        } catch (Exception e) {
            sendError(exchange, 400, "Invalid JSON body: " + e.getMessage());
            return null;
        }
    }

    /**
     * Serializes {@code obj} with Jackson, sets Content-Type: application/json,
     * and writes the response.
     */
    private void sendJson(HttpExchange exchange, int status, Object obj) throws IOException {
        byte[] body = mapper.writeValueAsBytes(obj);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        setCorsHeaders(exchange);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    /** Sends a JSON error response: {"error":"<message>"}. */
    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        ObjectNode err = mapper.createObjectNode();
        err.put("error", message);
        sendJson(exchange, status, err);
    }
}
