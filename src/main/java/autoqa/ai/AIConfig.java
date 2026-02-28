package autoqa.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Reads AI module settings from {@code config.properties} on the classpath and
 * exposes factory methods for {@link LLMClient}, {@link TestGenerator}, and
 * {@link LocatorHealer}.
 *
 * <p>All property keys mirror those documented in {@code config.properties}.
 * Each getter provides a safe default if the key is absent or unparseable.
 */
public class AIConfig {

    private static final Logger log = LoggerFactory.getLogger(AIConfig.class);

    private final Properties props;

    public AIConfig() {
        props = new Properties();
        try (InputStream is = getClass().getResourceAsStream("/config.properties")) {
            if (is != null) {
                props.load(is);
                log.debug("AIConfig loaded config.properties from classpath");
            } else {
                log.warn("config.properties not found on classpath — using all defaults");
            }
        } catch (IOException e) {
            log.warn("Could not load config.properties: {}", e.getMessage());
        }
    }

    // ── Property accessors ────────────────────────────────────────────────

    /** Whether the AI module is active; defaults to {@code true}. */
    public boolean isAiEnabled() {
        return Boolean.parseBoolean(props.getProperty("ai.enabled", "true"));
    }

    /** Base URL of the OpenAI-compatible endpoint; defaults to Ollama's local address. */
    public String getLlmBaseUrl() {
        return props.getProperty("ai.llm.base.url", "http://localhost:11434/v1");
    }

    /** Model identifier passed to the LLM; defaults to {@code qwen2.5-coder:32b}. */
    public String getLlmModel() {
        return props.getProperty("ai.llm.model", "qwen2.5-coder:32b");
    }

    /** Sampling temperature; defaults to {@code 0.1} for deterministic output. */
    public double getTemperature() {
        return parseDouble("ai.llm.temperature", 0.1);
    }

    /** Maximum completion tokens; defaults to {@code 4096}. */
    public int getMaxTokens() {
        return parseInt("ai.llm.max.tokens", 4096);
    }

    /** HTTP read-timeout in seconds for each LLM call; defaults to {@code 120}. */
    public int getTimeoutSec() {
        return parseInt("ai.llm.timeout.sec", 120);
    }

    /** Number of retries on 5xx or I/O errors; defaults to {@code 2}. */
    public int getRetryCount() {
        return parseInt("ai.llm.retry.count", 2);
    }

    /** Delay in milliseconds between retries; defaults to {@code 2000}. */
    public long getRetryDelayMs() {
        return parseLong("ai.llm.retry.delay.ms", 2000L);
    }

    /**
     * Directory where {@link TestGenerator} writes {@code .java} files.
     * Defaults to {@code generated-tests} relative to the working directory.
     */
    public Path getGeneratedTestsDir() {
        return Path.of(props.getProperty("ai.generated.tests.dir", "generated-tests"));
    }

    /**
     * Maximum number of characters from the live DOM snippet forwarded to the
     * {@link LocatorHealer} prompt; defaults to {@code 3000}.
     */
    public int getDomSnippetChars() {
        return parseInt("ai.healer.dom.snippet.chars", 3000);
    }

    // ── Factory methods ───────────────────────────────────────────────────

    /**
     * Creates a fully configured {@link LLMClient} using the current properties.
     *
     * @return new {@link LLMClient} instance
     */
    public LLMClient createLLMClient() {
        return new LLMClient(
                getLlmBaseUrl(),
                getLlmModel(),
                getTemperature(),
                getMaxTokens(),
                getTimeoutSec(),
                getRetryCount(),
                getRetryDelayMs()
        );
    }

    /**
     * Creates a {@link TestGenerator} backed by a fresh {@link LLMClient}.
     *
     * @return new {@link TestGenerator} instance
     */
    public TestGenerator createTestGenerator() {
        return new TestGenerator(createLLMClient(), getGeneratedTestsDir());
    }

    /**
     * Creates a {@link LocatorHealer} backed by a fresh {@link LLMClient}.
     *
     * @return new {@link LocatorHealer} instance
     */
    public LocatorHealer createLocatorHealer() {
        return new LocatorHealer(createLLMClient(), getDomSnippetChars());
    }

    // ── Property parsing helpers ──────────────────────────────────────────

    private double parseDouble(String key, double defaultVal) {
        String val = props.getProperty(key);
        if (val == null || val.isBlank()) return defaultVal;
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid value for '{}': '{}' — using default {}", key, val, defaultVal);
            return defaultVal;
        }
    }

    private int parseInt(String key, int defaultVal) {
        String val = props.getProperty(key);
        if (val == null || val.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid value for '{}': '{}' — using default {}", key, val, defaultVal);
            return defaultVal;
        }
    }

    private long parseLong(String key, long defaultVal) {
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
