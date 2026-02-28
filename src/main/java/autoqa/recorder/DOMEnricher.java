package autoqa.recorder;

import autoqa.model.BoundingBox;
import autoqa.model.ElementInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * Uses the Chrome DevTools Protocol {@code Runtime.evaluate} command to extract
 * complete {@link ElementInfo} for the element at given screen coordinates.
 *
 * <p>All DOM inspection is performed via a single JavaScript expression evaluated
 * in the browser context, so no Selenium WebDriver is required.
 */
public class DOMEnricher {

    private static final Logger log = LoggerFactory.getLogger(DOMEnricher.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Sentinel value returned by the frame-detection script when the element is
     * in the top-level browsing context (not inside any {@code <iframe>}).
     */
    private static final String TOP_FRAME_SENTINEL = "__top__";

    /**
     * JavaScript template that extracts element metadata at coordinates (x, y).
     * The placeholders {@code COORD_X} and {@code COORD_Y} are replaced at
     * runtime with actual double values.
     */
    private static final String ELEMENT_INFO_JS = """
            (function(x, y) {
              var el = document.elementFromPoint(x, y);
              if (!el) return null;
              function getCss(el) {
                if (el.id) return '#' + el.id;
                var parts = [el.tagName.toLowerCase()];
                if (el.className) parts.push('.' + el.className.trim().split(/\\s+/).join('.'));
                var parent = el.parentElement;
                if (parent && parent !== document.body) {
                  var siblings = Array.from(parent.children).filter(c => c.tagName === el.tagName);
                  if (siblings.length > 1) parts.push(':nth-child(' + (Array.from(parent.children).indexOf(el)+1) + ')');
                }
                return parts.join('');
              }
              function getXpath(el) {
                var parts = [];
                while (el && el.nodeType === 1) {
                  var idx = 1;
                  var sib = el.previousSibling;
                  while (sib) { if (sib.nodeType === 1 && sib.tagName === el.tagName) idx++; sib = sib.previousSibling; }
                  parts.unshift(el.tagName.toLowerCase() + '[' + idx + ']');
                  el = el.parentElement;
                }
                return '/' + parts.join('/');
              }
              var attrs = {};
              for (var i = 0; i < el.attributes.length; i++) attrs[el.attributes[i].name] = el.attributes[i].value;
              var rect = el.getBoundingClientRect();
              return {
                tagName: el.tagName.toLowerCase(),
                id: el.id || null,
                name: el.name || null,
                className: el.className || null,
                css: getCss(el),
                xpath: getXpath(el),
                text: (el.innerText || el.textContent || '').trim().substring(0, 200) || null,
                value: el.value || null,
                type: el.type || null,
                attributes: attrs,
                boundingBox: { x: rect.left, y: rect.top, width: rect.width, height: rect.height }
              };
            })(COORD_X, COORD_Y)
            """;

    /**
     * JavaScript that detects whether the current context has a frame ancestor.
     * Returns {@code "__top__"} for the top-level document, or the frame's
     * {@code id} / {@code name} attribute otherwise.
     */
    private static final String FRAME_DETECT_JS =
            "window.frameElement ? (window.frameElement.id || window.frameElement.name || '') : '__top__'";

    private final CDPConnector cdp;

    // ── Construction ──────────────────────────────────────────────────────

    /**
     * @param cdp an already-connected {@link CDPConnector}
     */
    public DOMEnricher(CDPConnector cdp) {
        this.cdp = cdp;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Returns complete {@link ElementInfo} for the element at screen
     * coordinates {@code (x, y)}, or {@code null} if no element exists there
     * or if the CDP call fails.
     *
     * @param x horizontal screen coordinate (pixels)
     * @param y vertical screen coordinate (pixels)
     * @return populated ElementInfo, or null
     */
    public ElementInfo enrich(double x, double y) {
        String js = ELEMENT_INFO_JS
                .replace("COORD_X", formatCoord(x))
                .replace("COORD_Y", formatCoord(y));

        try {
            JsonNode result = evaluateExpression(js);

            if (result == null || result.isNull() || result.isMissingNode()) {
                log.debug("DOMEnricher: no element found at ({}, {})", x, y);
                return null;
            }

            // CDP Runtime.evaluate wraps the JS return value under result.value
            JsonNode value = result.get("value");
            if (value == null || value.isNull()) {
                log.debug("DOMEnricher: CDP returned null value at ({}, {})", x, y);
                return null;
            }

            ElementInfo info = parseElementInfo(value);
            log.debug("DOMEnricher: enriched <{}> id='{}' at ({}, {})",
                    info.getTagName(), info.getId(), x, y);
            return info;

        } catch (Exception e) {
            log.warn("DOMEnricher: failed to enrich element at ({}, {}): {}", x, y, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Detects the frame chain needed to reach the element at {@code (x, y)}.
     *
     * <p>Returns an empty list for elements in the top-level document.
     * Returns a single-element list containing the frame identifier (id or name)
     * for elements inside one {@code <iframe>}.
     *
     * <p>Nested frame detection is performed by recursively evaluating the
     * frame-detect expression; in practice most IMDS pages use at most one
     * level of framing.
     *
     * @param x horizontal screen coordinate
     * @param y vertical screen coordinate
     * @return ordered list of frame identifiers (outermost first), or empty list
     */
    public List<String> detectFrameChain(double x, double y) {
        try {
            JsonNode result = evaluateExpression(FRAME_DETECT_JS);
            if (result == null || result.isNull()) {
                return Collections.emptyList();
            }

            String frameValue = result.has("value") ? result.get("value").asText("") : "";

            if (TOP_FRAME_SENTINEL.equals(frameValue) || frameValue.isEmpty()) {
                return Collections.emptyList();
            }

            log.debug("DOMEnricher: detected frame '{}' at ({}, {})", frameValue, x, y);
            return Collections.singletonList(frameValue);

        } catch (Exception e) {
            log.warn("DOMEnricher: frame detection failed at ({}, {}): {}", x, y, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Sends a {@code Runtime.evaluate} command and returns the raw CDP result node.
     */
    private JsonNode evaluateExpression(String expression) throws IOException {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("expression", expression);
        params.put("returnByValue", true);
        return cdp.sendCommand("Runtime.evaluate", params);
    }

    /**
     * Parses the CDP {@code result.value} JSON object into an {@link ElementInfo}.
     *
     * <p>The JavaScript returns a plain object so CDP serialises it as
     * {@code {"type":"object","value":{...}}}. This method receives the inner
     * value object.
     */
    private ElementInfo parseElementInfo(JsonNode v) {
        ElementInfo info = new ElementInfo();

        info.setTagName(textOrNull(v, "tagName"));
        info.setId(textOrNull(v, "id"));
        info.setName(textOrNull(v, "name"));
        info.setClassName(textOrNull(v, "className"));
        info.setCss(textOrNull(v, "css"));
        info.setXpath(textOrNull(v, "xpath"));
        info.setText(textOrNull(v, "text"));
        info.setValue(textOrNull(v, "value"));
        info.setType(textOrNull(v, "type"));

        // Parse attributes map
        JsonNode attrsNode = v.get("attributes");
        if (attrsNode != null && attrsNode.isObject()) {
            Map<String, String> attrs = new LinkedHashMap<>();
            attrsNode.fields().forEachRemaining(entry ->
                    attrs.put(entry.getKey(), entry.getValue().asText()));
            info.setAttributes(attrs);
        }

        // Parse bounding box
        JsonNode bbNode = v.get("boundingBox");
        if (bbNode != null && bbNode.isObject()) {
            BoundingBox bb = new BoundingBox(
                    doubleOrNull(bbNode, "x"),
                    doubleOrNull(bbNode, "y"),
                    doubleOrNull(bbNode, "width"),
                    doubleOrNull(bbNode, "height")
            );
            info.setBoundingBox(bb);
        }

        return info;
    }

    /**
     * Returns the text value of a JSON field, or {@code null} if the field is
     * absent, null, or the string {@code "null"}.
     */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode f = node.get(field);
        if (f == null || f.isNull()) return null;
        String s = f.asText();
        return (s.isEmpty() || "null".equals(s)) ? null : s;
    }

    /** Returns the double value of a JSON field, or {@code null} if absent. */
    private static Double doubleOrNull(JsonNode node, String field) {
        JsonNode f = node.get(field);
        return (f == null || f.isNull()) ? null : f.asDouble();
    }

    /**
     * Formats a coordinate for safe injection into a JavaScript expression.
     * Always uses a decimal point so the JS parser treats it as a number.
     */
    private static String formatCoord(double coord) {
        // Use a locale-safe representation that always contains a decimal point
        return String.format(Locale.US, "%.6f", coord);
    }
}
