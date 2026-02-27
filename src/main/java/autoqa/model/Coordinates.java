package autoqa.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Screen coordinates of a mouse event (click, scroll, context-menu, etc.).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Coordinates {

    @JsonProperty("x")
    private Double x;

    @JsonProperty("y")
    private Double y;

    public Coordinates() {}

    public Coordinates(Double x, Double y) {
        this.x = x;
        this.y = y;
    }

    public Double getX() { return x; }
    public Double getY() { return y; }

    public void setX(Double x) { this.x = x; }
    public void setY(Double y) { this.y = y; }

    @Override
    public String toString() {
        return String.format("Coordinates{x=%.1f, y=%.1f}", x, y);
    }
}
