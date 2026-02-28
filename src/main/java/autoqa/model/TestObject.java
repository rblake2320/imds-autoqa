package autoqa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A named test object stored in the {@link ObjectRepository}.
 *
 * <p>Equivalent to a UFT One Object Repository entry.  Each object has a
 * human-readable logical name (e.g. {@code "loginButton"}) and an ordered list
 * of locator strategies tried in sequence during playback — the same hierarchy
 * that UFT uses when resolving objects from its shared OR.
 *
 * <p>Example JSON:
 * <pre>{@code
 * {
 *   "name": "loginButton",
 *   "objectClass": "button",
 *   "description": "Primary login submit button",
 *   "locators": [
 *     { "strategy": "ID",  "value": "login-btn" },
 *     { "strategy": "CSS", "value": ".login-button" }
 *   ]
 * }
 * }</pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TestObject {

    @JsonProperty("name")
    private String name;

    /** UFT object class: button, link, input, select, checkbox, frame, etc. */
    @JsonProperty("objectClass")
    private String objectClass;

    @JsonProperty("description")
    private String description;

    /** Ordered locators — first match wins during playback. */
    @JsonProperty("locators")
    private List<ElementLocator> locators = new ArrayList<>();

    /** Extra identification properties (mirrors UFT TO properties). */
    @JsonProperty("properties")
    private Map<String, String> properties = new LinkedHashMap<>();

    /** URL pattern where this object exists (optional, for OR filtering). */
    @JsonProperty("pageUrl")
    private String pageUrl;

    public TestObject() {}

    public TestObject(String name, String objectClass) {
        this.name        = name;
        this.objectClass = objectClass;
    }

    // ── Getters ──────────────────────────────────────────────────────────

    public String               getName()         { return name; }
    public String               getObjectClass()  { return objectClass; }
    public String               getDescription()  { return description; }
    public List<ElementLocator> getLocators()     { return locators; }
    public Map<String, String>  getProperties()   { return properties; }
    public String               getPageUrl()      { return pageUrl; }

    // ── Setters ──────────────────────────────────────────────────────────

    public void setName(String name)                             { this.name = name; }
    public void setObjectClass(String objectClass)               { this.objectClass = objectClass; }
    public void setDescription(String description)               { this.description = description; }
    public void setLocators(List<ElementLocator> locators)       { this.locators = locators; }
    public void setProperties(Map<String, String> properties)    { this.properties = properties; }
    public void setPageUrl(String pageUrl)                       { this.pageUrl = pageUrl; }

    /** Adds a locator at the end of the fallback chain. */
    public void addLocator(ElementLocator locator) {
        locators.add(locator);
    }

    /** Convenience: add by strategy enum + value string. */
    public void addLocator(ElementLocator.Strategy strategy, String value) {
        locators.add(new ElementLocator(strategy, value));
    }

    /** Convert this TestObject's locators into an {@link ElementInfo} for LocatorResolver. */
    public ElementInfo toElementInfo() {
        ElementInfo ei = new ElementInfo();
        for (ElementLocator loc : locators) {
            switch (loc.getStrategy()) {
                case ID    -> ei.setId(loc.getValue());
                case NAME  -> ei.setName(loc.getValue());
                case CSS   -> ei.setCss(loc.getValue());
                case XPATH -> ei.setXpath(loc.getValue());
                default    -> {}
            }
        }
        if (properties.containsKey("text")) ei.setText(properties.get("text"));
        return ei;
    }

    @Override
    public String toString() {
        return String.format("TestObject{name='%s', class='%s', locators=%d}",
                name, objectClass, locators.size());
    }
}
