package autoqa.keyword;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single keyword-driven action — the IMDS AutoQA equivalent of a UFT One
 * keyword-driven step in the Keyword View.
 *
 * <p>A keyword action binds a logical keyword (e.g., {@code "click"},
 * {@code "verifyText"}) to:
 * <ul>
 *   <li>an optional target object name (referencing the Object Repository)</li>
 *   <li>a map of named parameters (e.g., {@code "value" → "admin"})</li>
 *   <li>an optional human-readable description</li>
 * </ul>
 *
 * <p>Example JSON representation (for use in a keyword test file):
 * <pre>{@code
 * {
 *   "keyword": "typeText",
 *   "target": "usernameField",
 *   "params": { "value": "admin" },
 *   "description": "Enter admin username"
 * }
 * }</pre>
 */
public class KeywordAction {

    private final String              keyword;
    private final String              target;
    private final Map<String, String> params;
    private final String              description;

    public KeywordAction(String keyword, String target,
                         Map<String, String> params, String description) {
        this.keyword     = keyword;
        this.target      = target;
        this.params      = params != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(params))
                : Collections.emptyMap();
        this.description = description;
    }

    public String              getKeyword()     { return keyword; }
    public String              getTarget()      { return target; }
    public Map<String, String> getParams()      { return params; }
    public String              getDescription() { return description; }

    /** Convenience: get a parameter value or a default. */
    public String param(String name, String defaultValue) {
        return params.getOrDefault(name, defaultValue);
    }

    @Override
    public String toString() {
        return String.format("KeywordAction{keyword='%s', target='%s', params=%s}",
                keyword, target, params);
    }
}
