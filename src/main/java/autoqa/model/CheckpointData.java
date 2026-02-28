package autoqa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data for a {@code CHECKPOINT} event — the IMDS AutoQA equivalent of
 * UFT One's verification checkpoints.
 *
 * <h3>UFT Checkpoint Types Supported</h3>
 * <ul>
 *   <li>{@link CheckpointType#TEXT}           — element text equals / contains expected value</li>
 *   <li>{@link CheckpointType#ELEMENT_EXISTS}  — element is present (and optionally visible)</li>
 *   <li>{@link CheckpointType#URL}             — current page URL contains substring</li>
 *   <li>{@link CheckpointType#TITLE}           — page title matches expected</li>
 *   <li>{@link CheckpointType#ATTRIBUTE}       — element attribute has expected value</li>
 *   <li>{@link CheckpointType#SCREENSHOT}      — pixel-diff against stored baseline PNG</li>
 * </ul>
 *
 * <h3>Match Modes</h3>
 * <ul>
 *   <li>{@link MatchMode#EQUALS}     — exact match (default for TEXT, URL, TITLE)</li>
 *   <li>{@link MatchMode#CONTAINS}   — substring match</li>
 *   <li>{@link MatchMode#STARTS_WITH} — prefix match</li>
 *   <li>{@link MatchMode#REGEX}      — full Java regex match</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CheckpointData {

    public enum CheckpointType {
        TEXT,           // verify element text
        ELEMENT_EXISTS, // verify element is in DOM
        URL,            // verify page URL
        TITLE,          // verify page title
        ATTRIBUTE,      // verify element attribute value
        SCREENSHOT      // pixel-diff vs baseline image
    }

    public enum MatchMode {
        EQUALS,
        CONTAINS,
        STARTS_WITH,
        REGEX
    }

    @JsonProperty("checkpointType")
    private CheckpointType checkpointType;

    /** Expected value (text, URL substring, regex, etc.). */
    @JsonProperty("expectedValue")
    private String expectedValue;

    /** Match mode. Defaults to EQUALS if not specified. */
    @JsonProperty("matchMode")
    private MatchMode matchMode = MatchMode.EQUALS;

    /** For ATTRIBUTE checkpoints: name of the attribute to check. */
    @JsonProperty("attributeName")
    private String attributeName;

    /** For SCREENSHOT checkpoints: path to baseline PNG relative to the recording. */
    @JsonProperty("baselineImagePath")
    private String baselineImagePath;

    /**
     * For SCREENSHOT checkpoints: maximum allowed pixel-difference ratio (0.0–1.0).
     * Default 0.02 = 2% of pixels may differ.
     */
    @JsonProperty("screenshotThreshold")
    private double screenshotThreshold = 0.02;

    /** If true, text/URL comparison is case-sensitive. Default: false. */
    @JsonProperty("caseSensitive")
    private boolean caseSensitive = false;

    /**
     * Name of this checkpoint, used in Allure step labelling.
     * Generated automatically if not set.
     */
    @JsonProperty("checkpointName")
    private String checkpointName;

    public CheckpointData() {}

    // ── Factory helpers ───────────────────────────────────────────────────

    public static CheckpointData textEquals(String expected) {
        CheckpointData cp = new CheckpointData();
        cp.checkpointType = CheckpointType.TEXT;
        cp.expectedValue  = expected;
        cp.matchMode      = MatchMode.EQUALS;
        return cp;
    }

    public static CheckpointData textContains(String expected) {
        CheckpointData cp = new CheckpointData();
        cp.checkpointType = CheckpointType.TEXT;
        cp.expectedValue  = expected;
        cp.matchMode      = MatchMode.CONTAINS;
        return cp;
    }

    public static CheckpointData elementExists() {
        CheckpointData cp = new CheckpointData();
        cp.checkpointType = CheckpointType.ELEMENT_EXISTS;
        return cp;
    }

    public static CheckpointData urlContains(String urlFragment) {
        CheckpointData cp = new CheckpointData();
        cp.checkpointType = CheckpointType.URL;
        cp.expectedValue  = urlFragment;
        cp.matchMode      = MatchMode.CONTAINS;
        return cp;
    }

    public static CheckpointData screenshot(String baselinePath, double threshold) {
        CheckpointData cp = new CheckpointData();
        cp.checkpointType      = CheckpointType.SCREENSHOT;
        cp.baselineImagePath   = baselinePath;
        cp.screenshotThreshold = threshold;
        return cp;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────

    public CheckpointType getCheckpointType()     { return checkpointType; }
    public String         getExpectedValue()      { return expectedValue; }
    public MatchMode      getMatchMode()          { return matchMode != null ? matchMode : MatchMode.EQUALS; }
    public String         getAttributeName()      { return attributeName; }
    public String         getBaselineImagePath()  { return baselineImagePath; }
    public double         getScreenshotThreshold(){ return screenshotThreshold; }
    public boolean        isCaseSensitive()       { return caseSensitive; }
    public String         getCheckpointName()     { return checkpointName; }

    public void setCheckpointType(CheckpointType t)    { this.checkpointType = t; }
    public void setExpectedValue(String v)             { this.expectedValue = v; }
    public void setMatchMode(MatchMode m)              { this.matchMode = m; }
    public void setAttributeName(String a)             { this.attributeName = a; }
    public void setBaselineImagePath(String p)         { this.baselineImagePath = p; }
    public void setScreenshotThreshold(double t)       { this.screenshotThreshold = t; }
    public void setCaseSensitive(boolean cs)           { this.caseSensitive = cs; }
    public void setCheckpointName(String n)            { this.checkpointName = n; }
}
