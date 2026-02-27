package autoqa.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Screen coordinates and dimensions of an element's bounding rectangle,
 * captured at the time of the recorded event.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BoundingBox {

    @JsonProperty("x")
    private Double x;

    @JsonProperty("y")
    private Double y;

    @JsonProperty("width")
    private Double width;

    @JsonProperty("height")
    private Double height;

    public BoundingBox() {}

    public BoundingBox(Double x, Double y, Double width, Double height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public Double getX()      { return x; }
    public Double getY()      { return y; }
    public Double getWidth()  { return width; }
    public Double getHeight() { return height; }

    public void setX(Double x)           { this.x = x; }
    public void setY(Double y)           { this.y = y; }
    public void setWidth(Double width)   { this.width = width; }
    public void setHeight(Double height) { this.height = height; }

    @Override
    public String toString() {
        return String.format("BoundingBox{x=%.1f, y=%.1f, w=%.1f, h=%.1f}", x, y, width, height);
    }
}
