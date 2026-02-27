package autoqa.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Reads and writes {@link RecordedSession} objects to/from JSON files.
 *
 * <p>On read: validates the JSON against {@code event-schema.json} before
 * deserializing, and rejects sessions with unsupported schema versions.
 *
 * <p>On write: pretty-prints for human readability; schema version is
 * set to {@link RecordedSession#CURRENT_SCHEMA_VERSION}.
 */
public class RecordingIO {

    private static final Logger log = LoggerFactory.getLogger(RecordingIO.class);
    private static final String SCHEMA_RESOURCE = "/event-schema.json";

    /** Singleton ObjectMapper — thread-safe after configuration. */
    private static final ObjectMapper MAPPER;

    static {
        MAPPER = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /** Loaded once from classpath; null if schema resource is missing. */
    private static volatile JsonSchema JSON_SCHEMA = null;

    private RecordingIO() {}

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Reads a {@link RecordedSession} from a JSON file.
     *
     * @param path path to the recording JSON file
     * @return the deserialized session
     * @throws IOException          if the file cannot be read or parsed
     * @throws SchemaVersionException if the schema version is not supported
     */
    public static RecordedSession read(Path path) throws IOException {
        log.debug("Reading recording from: {}", path);
        String json = Files.readString(path);
        validateSchema(json, path.toString());
        RecordedSession session = MAPPER.readValue(json, RecordedSession.class);
        if (!session.isVersionSupported()) {
            throw new SchemaVersionException(
                    "Unsupported schema version: " + session.getSchemaVersion()
                    + " (expected: " + RecordedSession.CURRENT_SCHEMA_VERSION + ")");
        }
        log.info("Loaded session '{}' with {} events from {}", session.getSessionId(),
                session.getEventCount(), path);
        return session;
    }

    /**
     * Reads a {@link RecordedSession} from a File.
     */
    public static RecordedSession read(File file) throws IOException {
        return read(file.toPath());
    }

    /**
     * Writes a {@link RecordedSession} to a JSON file (pretty-printed).
     *
     * @param session the session to serialize
     * @param path    the destination file path (parent directories are created)
     * @throws IOException if the file cannot be written
     */
    public static void write(RecordedSession session, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        MAPPER.writeValue(path.toFile(), session);
        log.info("Wrote session '{}' ({} events) to {}", session.getSessionId(),
                session.getEventCount(), path);
    }

    /**
     * Serializes a {@link RecordedSession} to a JSON string.
     */
    public static String toJson(RecordedSession session) throws IOException {
        return MAPPER.writeValueAsString(session);
    }

    /**
     * Deserializes a {@link RecordedSession} from a JSON string (no schema validation).
     * Use {@link #read(Path)} for validated loading from files.
     */
    public static RecordedSession fromJson(String json) throws IOException {
        return MAPPER.readValue(json, RecordedSession.class);
    }

    /** Returns the shared ObjectMapper (for use in tests and other modules). */
    public static ObjectMapper getMapper() { return MAPPER; }

    // ── Schema validation ─────────────────────────────────────────────────

    private static void validateSchema(String json, String source) {
        JsonSchema schema = getSchema();
        if (schema == null) {
            log.warn("event-schema.json not found on classpath — skipping schema validation");
            return;
        }
        try {
            Set<ValidationMessage> errors = schema.validate(MAPPER.readTree(json));
            if (!errors.isEmpty()) {
                StringBuilder sb = new StringBuilder("Schema validation failed for ").append(source).append(":\n");
                errors.forEach(e -> sb.append("  ").append(e.getMessage()).append("\n"));
                throw new SchemaValidationException(sb.toString());
            }
        } catch (IOException e) {
            log.warn("Could not parse JSON for schema validation: {}", e.getMessage());
        }
    }

    private static JsonSchema getSchema() {
        if (JSON_SCHEMA == null) {
            synchronized (RecordingIO.class) {
                if (JSON_SCHEMA == null) {
                    try (InputStream is = RecordingIO.class.getResourceAsStream(SCHEMA_RESOURCE)) {
                        if (is == null) {
                            log.warn("Schema resource not found: {}", SCHEMA_RESOURCE);
                            return null;
                        }
                        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
                        JSON_SCHEMA = factory.getSchema(is);
                        log.debug("JSON schema loaded from classpath: {}", SCHEMA_RESOURCE);
                    } catch (IOException e) {
                        log.warn("Failed to load schema: {}", e.getMessage());
                    }
                }
            }
        }
        return JSON_SCHEMA;
    }

    // ── Exceptions ────────────────────────────────────────────────────────

    public static class SchemaVersionException extends RuntimeException {
        public SchemaVersionException(String msg) { super(msg); }
    }

    public static class SchemaValidationException extends RuntimeException {
        public SchemaValidationException(String msg) { super(msg); }
    }
}
