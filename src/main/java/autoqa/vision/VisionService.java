package autoqa.vision;

import autoqa.model.UIElement;
import org.openqa.selenium.WebDriver;
import java.util.List;

/**
 * Interface for optional vision-based UI element detection.
 * Default implementation is StubVisionService (returns empty results).
 */
public interface VisionService {

    /**
     * Analyzes a screenshot and returns detected UI elements.
     * @param driver WebDriver instance (used to take screenshot)
     * @return list of detected UI elements; empty if vision is disabled or fails
     */
    List<UIElement> analyzeScreenshot(WebDriver driver);

    /**
     * Checks whether a popup or modal is visible in the current screenshot.
     * Used by PopupSentinel as an additional detection layer.
     * @return true if a popup/modal is detected with sufficient confidence
     */
    boolean isPopupPresent(WebDriver driver);
}
