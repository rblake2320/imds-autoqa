package autoqa.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Vision service output — a UI element detected in a screenshot.
 * Produced by {@code VisionService.analyzeScreenshot()} and used to
 * enrich weak locators in DOMEnricher and detect popups in PopupSentinel.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UIElement {

    /** Component type inferred by vision (e.g., "button", "input", "modal", "alert"). */
    @JsonProperty("type")
    private String type;

    /** Visible text on the element as read by vision. */
    @JsonProperty("text")
    private String text;

    /** Confidence score from the vision model [0.0 – 1.0]. */
    @JsonProperty("confidence")
    private Double confidence;

    @JsonProperty("boundingBox")
    private BoundingBox boundingBox;

    public UIElement() {}

    public UIElement(String type, String text, Double confidence, BoundingBox boundingBox) {
        this.type        = type;
        this.text        = text;
        this.confidence  = confidence;
        this.boundingBox = boundingBox;
    }

    public String      getType()        { return type; }
    public String      getText()        { return text; }
    public Double      getConfidence()  { return confidence; }
    public BoundingBox getBoundingBox() { return boundingBox; }

    public void setType(String type)               { this.type = type; }
    public void setText(String text)               { this.text = text; }
    public void setConfidence(Double confidence)   { this.confidence = confidence; }
    public void setBoundingBox(BoundingBox bb)     { this.boundingBox = bb; }

    /** True if confidence meets the configured minimum threshold. */
    public boolean isConfidentEnough(double minThreshold) {
        return confidence != null && confidence >= minThreshold;
    }

    @Override
    public String toString() {
        return String.format("UIElement{type='%s', text='%s', confidence=%.2f}",
                type, text, confidence != null ? confidence : 0.0);
    }
}
