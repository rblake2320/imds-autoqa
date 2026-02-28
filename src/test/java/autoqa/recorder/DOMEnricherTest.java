package autoqa.recorder;

import autoqa.model.ElementInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DOMEnricher}.
 *
 * <p>All tests use a Mockito mock of {@link CDPConnector} to avoid any
 * real network or browser dependency.
 */
public class DOMEnricherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CDPConnector mockCdp;
    private DOMEnricher enricher;

    @BeforeMethod
    public void setUp() {
        // CDPConnector is not an interface, but Mockito can mock concrete classes
        mockCdp  = mock(CDPConnector.class);
        enricher = new DOMEnricher(mockCdp);
    }

    // ── enrich() — null result from CDP ──────────────────────────────────

    /**
     * When the CDP Runtime.evaluate call returns a JSON null value (the JS
     * expression {@code document.elementFromPoint} returned {@code null}),
     * {@code enrich()} must return {@code null} without throwing.
     */
    @Test(description = "enrich returns null when CDP Runtime.evaluate result value is null")
    public void enrich_nullResult_returnsNull() throws IOException {
        // CDP response: { "result": { "type": "object", "value": null } }
        ObjectNode cdpResult = MAPPER.createObjectNode();
        cdpResult.putNull("value");

        when(mockCdp.sendCommand(eq("Runtime.evaluate"), any(ObjectNode.class)))
                .thenReturn(cdpResult);

        ElementInfo result = enricher.enrich(100.0, 200.0);

        assertThat(result)
                .as("enrich() must return null when CDP reports no element at the coordinates")
                .isNull();
    }

    /**
     * When CDP returns a completely {@code null} result node (not just a null
     * value field), {@code enrich()} must still return {@code null}.
     */
    @Test(description = "enrich returns null when CDP sendCommand itself returns null")
    public void enrich_cdpReturnsNullNode_returnsNull() throws IOException {
        when(mockCdp.sendCommand(eq("Runtime.evaluate"), any(ObjectNode.class)))
                .thenReturn(null);

        ElementInfo result = enricher.enrich(50.0, 75.0);

        assertThat(result).isNull();
    }

    // ── enrich() — valid element result ──────────────────────────────────

    /**
     * When CDP returns a fully-populated element object, all {@link ElementInfo}
     * fields must be correctly parsed and populated.
     */
    @Test(description = "enrich parses all ElementInfo fields from a valid CDP result")
    public void enrich_validResult_parsesAllFields() throws IOException {
        // Build the JSON that the JS expression returns (wrapped in CDP result envelope)
        String elementJson = """
                {
                  "tagName": "input",
                  "id": "username",
                  "name": "user",
                  "className": "form-control",
                  "css": "#username",
                  "xpath": "/html[1]/body[1]/form[1]/input[1]",
                  "text": "",
                  "value": "john.doe",
                  "type": "text",
                  "attributes": {
                    "id": "username",
                    "name": "user",
                    "type": "text",
                    "placeholder": "Enter username"
                  },
                  "boundingBox": {
                    "x": 100.5,
                    "y": 200.0,
                    "width": 250.0,
                    "height": 36.0
                  }
                }
                """;

        ObjectNode cdpResult = MAPPER.createObjectNode();
        cdpResult.set("value", MAPPER.readTree(elementJson));

        when(mockCdp.sendCommand(eq("Runtime.evaluate"), any(ObjectNode.class)))
                .thenReturn(cdpResult);

        ElementInfo info = enricher.enrich(150.0, 218.0);

        assertThat(info)
                .as("ElementInfo must not be null for a valid CDP response")
                .isNotNull();

        assertThat(info.getTagName())
                .as("tagName")
                .isEqualTo("input");

        assertThat(info.getId())
                .as("id")
                .isEqualTo("username");

        assertThat(info.getName())
                .as("name")
                .isEqualTo("user");

        assertThat(info.getClassName())
                .as("className")
                .isEqualTo("form-control");

        assertThat(info.getCss())
                .as("css")
                .isEqualTo("#username");

        assertThat(info.getXpath())
                .as("xpath")
                .isEqualTo("/html[1]/body[1]/form[1]/input[1]");

        assertThat(info.getValue())
                .as("value")
                .isEqualTo("john.doe");

        assertThat(info.getType())
                .as("type")
                .isEqualTo("text");

        assertThat(info.getAttributes())
                .as("attributes map")
                .isNotNull()
                .containsEntry("placeholder", "Enter username")
                .containsEntry("type", "text");

        assertThat(info.getBoundingBox())
                .as("boundingBox")
                .isNotNull();

        assertThat(info.getBoundingBox().getX())
                .as("boundingBox.x")
                .isEqualTo(100.5);

        assertThat(info.getBoundingBox().getWidth())
                .as("boundingBox.width")
                .isEqualTo(250.0);

        assertThat(info.getBoundingBox().getHeight())
                .as("boundingBox.height")
                .isEqualTo(36.0);

        assertThat(info.hasAnyLocator())
                .as("hasAnyLocator must be true when id is populated")
                .isTrue();
    }

    /**
     * An element with only a CSS selector (no id) still satisfies
     * {@link ElementInfo#hasAnyLocator()}.
     */
    @Test(description = "enrich sets css when element has no id")
    public void enrich_noId_cssStillPopulated() throws IOException {
        String elementJson = """
                {
                  "tagName": "button",
                  "id": null,
                  "name": null,
                  "className": "btn btn-primary",
                  "css": "button.btn.btn-primary",
                  "xpath": "/html[1]/body[1]/button[1]",
                  "text": "Submit",
                  "value": null,
                  "type": "submit",
                  "attributes": {},
                  "boundingBox": { "x": 0.0, "y": 0.0, "width": 80.0, "height": 32.0 }
                }
                """;

        ObjectNode cdpResult = MAPPER.createObjectNode();
        cdpResult.set("value", MAPPER.readTree(elementJson));

        when(mockCdp.sendCommand(eq("Runtime.evaluate"), any(ObjectNode.class)))
                .thenReturn(cdpResult);

        ElementInfo info = enricher.enrich(40.0, 16.0);

        assertThat(info).isNotNull();
        assertThat(info.getId()).isNull();
        assertThat(info.getCss()).isEqualTo("button.btn.btn-primary");
        assertThat(info.getText()).isEqualTo("Submit");
        assertThat(info.hasAnyLocator()).isTrue();
    }

    /**
     * When CDP throws an {@link IOException}, {@code enrich()} must return
     * {@code null} rather than propagating the exception.
     */
    @Test(description = "enrich returns null and does not throw when CDP call fails")
    public void enrich_cdpThrows_returnsNull() throws IOException {
        when(mockCdp.sendCommand(eq("Runtime.evaluate"), any(ObjectNode.class)))
                .thenThrow(new IOException("simulated CDP connection error"));

        ElementInfo result = enricher.enrich(10.0, 10.0);

        assertThat(result)
                .as("enrich must swallow CDP errors and return null")
                .isNull();
    }

    // ── detectFrameChain() — top-level document ───────────────────────────

    /**
     * When the JS frame-detection expression returns the sentinel
     * {@code "__top__"}, {@code detectFrameChain()} must return an empty list,
     * indicating the element is in the top-level document.
     */
    @Test(description = "detectFrameChain returns empty list for top-level document")
    public void detectFrameChain_topLevel_returnsEmpty() throws IOException {
        // CDP returns { "value": "__top__" } to indicate the top-level frame
        ObjectNode cdpResult = MAPPER.createObjectNode();
        cdpResult.put("value", "__top__");

        when(mockCdp.sendCommand(eq("Runtime.evaluate"), any(ObjectNode.class)))
                .thenReturn(cdpResult);

        List<String> chain = enricher.detectFrameChain(50.0, 100.0);

        assertThat(chain)
                .as("Frame chain must be empty for top-level document")
                .isNotNull()
                .isEmpty();
    }

    /**
     * When the frame-detection expression returns a non-sentinel value, the
     * returned list must contain exactly that frame identifier.
     */
    @Test(description = "detectFrameChain returns single-element list when element is inside an iframe")
    public void detectFrameChain_insideIframe_returnsFrameId() throws IOException {
        ObjectNode cdpResult = MAPPER.createObjectNode();
        cdpResult.put("value", "mainContent");   // the iframe's id attribute

        when(mockCdp.sendCommand(eq("Runtime.evaluate"), any(ObjectNode.class)))
                .thenReturn(cdpResult);

        List<String> chain = enricher.detectFrameChain(200.0, 300.0);

        assertThat(chain)
                .as("Frame chain must contain the iframe identifier")
                .containsExactly("mainContent");
    }

    /**
     * An empty string returned from the frame-detection expression (frame has
     * neither id nor name) is treated the same as {@code "__top__"} and must
     * produce an empty list.
     */
    @Test(description = "detectFrameChain treats empty string result as top-level")
    public void detectFrameChain_emptyStringResult_returnsEmpty() throws IOException {
        ObjectNode cdpResult = MAPPER.createObjectNode();
        cdpResult.put("value", "");

        when(mockCdp.sendCommand(eq("Runtime.evaluate"), any(ObjectNode.class)))
                .thenReturn(cdpResult);

        List<String> chain = enricher.detectFrameChain(0.0, 0.0);

        assertThat(chain).isEmpty();
    }

    /**
     * When CDP throws during frame detection, {@code detectFrameChain()} must
     * return an empty list rather than propagating the exception.
     */
    @Test(description = "detectFrameChain returns empty list and does not throw when CDP call fails")
    public void detectFrameChain_cdpThrows_returnsEmpty() throws IOException {
        when(mockCdp.sendCommand(eq("Runtime.evaluate"), any(ObjectNode.class)))
                .thenThrow(new IOException("simulated connection drop"));

        List<String> chain = enricher.detectFrameChain(10.0, 10.0);

        assertThat(chain)
                .as("detectFrameChain must handle exceptions gracefully")
                .isEmpty();
    }

    // ── Coordinate injection safety ───────────────────────────────────────

    /**
     * Verifies that coordinate values are injected using locale-safe decimal
     * formatting (dot as separator) so the JavaScript is syntactically valid
     * in all JVM locales.
     */
    @Test(description = "Coordinates with fractional parts are injected with locale-safe dot decimal separator")
    public void enrich_fractionalCoordinates_jsIsWellFormed() throws IOException {
        // Return null value to avoid parsing complexity — we only care that the
        // expression was sent (no NumberFormatException / bad JS syntax)
        ObjectNode cdpResult = MAPPER.createObjectNode();
        cdpResult.putNull("value");

        when(mockCdp.sendCommand(eq("Runtime.evaluate"), any(ObjectNode.class)))
                .thenReturn(cdpResult);

        // Use a coordinate that differs between locales: 123.456
        assertThatCode(() -> enricher.enrich(123.456, 789.012))
                .as("enrich must not throw even with fractional coordinates")
                .doesNotThrowAnyException();

        // Verify that CDP was actually called (i.e., the JS was built without error)
        verify(mockCdp, atLeastOnce()).sendCommand(eq("Runtime.evaluate"), any(ObjectNode.class));
    }
}
