package autoqa.accessibility;

import java.util.List;

/**
 * A single WCAG accessibility violation found by the axe-core engine.
 *
 * <p>Maps to the {@code violations[]} array in the axe-core result JSON.
 */
public class AccessibilityViolation {

    private final String       id;
    private final String       description;
    private final String       impact;       // critical, serious, moderate, minor
    private final String       wcagLevel;    // e.g., "wcag2a", "wcag2aa", "best-practice"
    private final List<String> affectedNodes; // CSS selectors of affected DOM nodes

    public AccessibilityViolation(String id, String description, String impact,
                                  String wcagLevel, List<String> affectedNodes) {
        this.id            = id;
        this.description   = description;
        this.impact        = impact;
        this.wcagLevel     = wcagLevel;
        this.affectedNodes = affectedNodes != null ? List.copyOf(affectedNodes) : List.of();
    }

    public String       getId()            { return id; }
    public String       getDescription()  { return description; }
    public String       getImpact()       { return impact; }
    public String       getWcagLevel()    { return wcagLevel; }
    public List<String> getAffectedNodes(){ return affectedNodes; }

    /** True if this violation is critical or serious (WCAG A/AA level). */
    public boolean isSevere() {
        return "critical".equalsIgnoreCase(impact) || "serious".equalsIgnoreCase(impact);
    }

    @Override
    public String toString() {
        return String.format("[%s] %s (impact=%s, wcag=%s, nodes=%d)",
                id, description, impact, wcagLevel, affectedNodes.size());
    }
}
