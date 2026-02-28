package autoqa.vision;

import autoqa.model.UIElement;
import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;

/**
 * Default no-op implementation of VisionService.
 * Used when vision.enabled=false (the default).
 * Always returns empty lists and false — guarantees the system works without a GPU/NIM endpoint.
 */
public class StubVisionService implements VisionService {
    private static final Logger log = LoggerFactory.getLogger(StubVisionService.class);

    @Override
    public List<UIElement> analyzeScreenshot(WebDriver driver) {
        log.debug("VisionService: stub — returning empty element list");
        return List.of();
    }

    @Override
    public boolean isPopupPresent(WebDriver driver) {
        log.debug("VisionService: stub — returning false for popup check");
        return false;
    }
}
