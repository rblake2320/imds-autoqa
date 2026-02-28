package autoqa.vision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Creates the appropriate VisionService based on config.
 */
public class VisionServiceFactory {
    private static final Logger log = LoggerFactory.getLogger(VisionServiceFactory.class);

    private VisionServiceFactory() {}

    public static VisionService create() {
        Properties props = new Properties();
        try (InputStream is = VisionServiceFactory.class.getResourceAsStream("/config.properties")) {
            if (is != null) props.load(is);
        } catch (IOException e) { /* use defaults */ }

        boolean enabled = Boolean.parseBoolean(props.getProperty("vision.enabled", "false"));
        if (!enabled) {
            log.info("Vision disabled (vision.enabled=false) — using StubVisionService");
            return new StubVisionService();
        }

        log.info("Vision enabled — creating NvidiaVisionClient");
        return NvidiaVisionClient.fromConfig();
    }
}
