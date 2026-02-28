package autoqa.vision;

import autoqa.model.UIElement;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import java.util.List;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

public class NvidiaVisionClientTest {
    private WireMockServer wireMock;

    @BeforeClass
    public void setup() {
        wireMock = new WireMockServer(0);
        wireMock.start();
    }

    @AfterClass
    public void teardown() { wireMock.stop(); }

    @Test
    public void analyzeScreenshot_parsesElements() {
        wireMock.stubFor(post(anyUrl())
            .willReturn(okJson("""
                {"choices":[{"message":{"content":"button|Submit|100,200,80,36\\nlink|Home|0,0,60,20"}}]}
                """)));

        NvidiaVisionClient client = new NvidiaVisionClient(
            "http://localhost:" + wireMock.port() + "/test",
            "test-key", 10, 0.75
        );

        WebDriver driver = mock(WebDriver.class, withSettings().extraInterfaces(TakesScreenshot.class));
        when(((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES)).thenReturn(new byte[]{1, 2, 3});

        List<UIElement> elements = client.analyzeScreenshot(driver);
        assertThat(elements).hasSize(2);
        assertThat(elements.get(0).getType()).isEqualTo("button");
        assertThat(elements.get(0).getText()).isEqualTo("Submit");
        assertThat(elements.get(0).getBoundingBox().getX()).isEqualTo(100.0);
        assertThat(elements.get(1).getType()).isEqualTo("link");
    }

    @Test
    public void isPopupPresent_yesResponse_returnsTrue() {
        wireMock.stubFor(post(anyUrl())
            .willReturn(okJson("""
                {"choices":[{"message":{"content":"YES, there is a modal dialog visible."}}]}
                """)));

        NvidiaVisionClient client = new NvidiaVisionClient(
            "http://localhost:" + wireMock.port() + "/test",
            "test-key", 10, 0.75
        );

        WebDriver driver = mock(WebDriver.class, withSettings().extraInterfaces(TakesScreenshot.class));
        when(((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES)).thenReturn(new byte[]{1, 2, 3});

        assertThat(client.isPopupPresent(driver)).isTrue();
    }

    @Test
    public void analyzeScreenshot_nimError_returnsEmptyList() {
        wireMock.stubFor(post(anyUrl()).willReturn(aResponse().withStatus(500)));

        NvidiaVisionClient client = new NvidiaVisionClient(
            "http://localhost:" + wireMock.port() + "/test",
            "test-key", 10, 0.75
        );

        WebDriver driver = mock(WebDriver.class, withSettings().extraInterfaces(TakesScreenshot.class));
        when(((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES)).thenReturn(new byte[]{1, 2, 3});

        assertThat(client.analyzeScreenshot(driver)).isEmpty();
    }
}
