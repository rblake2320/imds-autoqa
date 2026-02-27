package autoqa.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single resolved locator with its strategy and value.
 * Used by LocatorResolver to record which locator strategy succeeded,
 * and by HealingInterceptor to track what was attempted before healing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ElementLocator {

    public enum Strategy { ID, NAME, CSS, XPATH, TEXT, HEALED }

    @JsonProperty("strategy")
    private Strategy strategy;

    @JsonProperty("value")
    private String value;

    /** True if this locator was produced by the LLM healer. */
    @JsonProperty("healed")
    private boolean healed;

    public ElementLocator() {}

    public ElementLocator(Strategy strategy, String value) {
        this.strategy = strategy;
        this.value    = value;
        this.healed   = (strategy == Strategy.HEALED);
    }

    public Strategy getStrategy() { return strategy; }
    public String   getValue()    { return value; }
    public boolean  isHealed()    { return healed; }

    public void setStrategy(Strategy strategy) { this.strategy = strategy; }
    public void setValue(String value)         { this.value = value; }
    public void setHealed(boolean healed)      { this.healed = healed; }

    @Override
    public String toString() {
        return String.format("ElementLocator{%s='%s'%s}", strategy, value, healed ? " [HEALED]" : "");
    }
}
