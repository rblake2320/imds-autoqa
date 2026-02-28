package autoqa.vision;

import autoqa.model.UIElement;
import org.openqa.selenium.WebDriver;
import org.testng.annotations.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

public class StubVisionServiceTest {

    @Test
    public void analyzeScreenshot_alwaysReturnsEmpty() {
        VisionService service = new StubVisionService();
        WebDriver driver = mock(WebDriver.class);
        List<UIElement> result = service.analyzeScreenshot(driver);
        assertThat(result).isEmpty();
        verifyNoInteractions(driver); // stub never calls driver
    }

    @Test
    public void isPopupPresent_alwaysReturnsFalse() {
        VisionService service = new StubVisionService();
        WebDriver driver = mock(WebDriver.class);
        assertThat(service.isPopupPresent(driver)).isFalse();
        verifyNoInteractions(driver);
    }

    @Test
    public void factory_withVisionDisabled_returnsStub() {
        // vision.enabled defaults to false in config.properties
        VisionService service = VisionServiceFactory.create();
        assertThat(service).isInstanceOf(StubVisionService.class);
    }
}
