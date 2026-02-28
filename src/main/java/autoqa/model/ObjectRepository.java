package autoqa.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared Object Repository — the IMDS AutoQA equivalent of UFT One's
 * {@code .tsr} (Test Shared Repository) file.
 *
 * <p>Stores named {@link TestObject} entries with their ordered locator
 * fallback chains.  During playback, objects are resolved by logical name
 * rather than raw locator strings embedded in recordings — this means a
 * single OR update fixes every test that references the object, exactly
 * as it does in UFT's shared OR.
 *
 * <p>Default file: {@code object-repository.json} in the working directory.
 * The format is stable JSON; the {@code schemaVersion} field allows future
 * migrations.
 *
 * <h3>UFT Parity</h3>
 * <ul>
 *   <li>Named test objects with hierarchical locator chains (ID → CSS → XPath)</li>
 *   <li>Object class tagging (button, link, input, select, …)</li>
 *   <li>Custom identification properties</li>
 *   <li>Import from recording: extract ElementInfo from a recorded session and
 *       promote it to a named OR entry</li>
 *   <li>OR comparison (diff two repositories)</li>
 *   <li>OR merge (combine two repositories)</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ObjectRepository {

    public static final String CURRENT_SCHEMA_VERSION = "1.0";
    public static final String DEFAULT_FILENAME       = "object-repository.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .findAndRegisterModules();

    @JsonProperty("schemaVersion")
    private String schemaVersion = CURRENT_SCHEMA_VERSION;

    @JsonProperty("description")
    private String description;

    /** Named test objects, keyed by logical name. Order is preserved (LinkedHashMap). */
    @JsonProperty("objects")
    private Map<String, TestObject> objects = new LinkedHashMap<>();

    // ── Factory ───────────────────────────────────────────────────────────

    public static ObjectRepository empty() {
        return new ObjectRepository();
    }

    /**
     * Loads an OR from a JSON file.
     * @throws IOException if the file cannot be read or is malformed
     */
    public static ObjectRepository load(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), ObjectRepository.class);
    }

    /**
     * Loads from the default file in the current directory if it exists,
     * otherwise returns an empty OR.
     */
    public static ObjectRepository loadOrEmpty(String dir) {
        Path p = Path.of(dir).resolve(DEFAULT_FILENAME);
        if (Files.exists(p)) {
            try {
                return load(p);
            } catch (IOException e) {
                // corrupt file — start fresh
            }
        }
        return empty();
    }

    // ── Persistence ───────────────────────────────────────────────────────

    /** Serialises this OR to a JSON file (pretty-printed). */
    public void save(Path path) throws IOException {
        Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
        MAPPER.writeValue(path.toFile(), this);
    }

    // ── CRUD ──────────────────────────────────────────────────────────────

    /**
     * Returns the named test object.
     * @throws IllegalArgumentException if the name is not found
     */
    public TestObject get(String name) {
        TestObject obj = objects.get(name);
        if (obj == null) {
            throw new IllegalArgumentException(
                    "Unknown test object: '" + name + "'. Known: " + objects.keySet());
        }
        return obj;
    }

    /** Returns null instead of throwing if the object is not in the OR. */
    public TestObject find(String name) {
        return objects.get(name);
    }

    /** Adds or replaces a test object in the OR. */
    public void add(TestObject obj) {
        obj.setName(obj.getName()); // ensure name is set
        objects.put(obj.getName(), obj);
    }

    /** Removes a test object by logical name. Returns true if it existed. */
    public boolean remove(String name) {
        return objects.remove(name) != null;
    }

    /** True if the OR contains an object with this logical name. */
    public boolean contains(String name) {
        return objects.containsKey(name);
    }

    public Collection<TestObject> all() {
        return objects.values();
    }

    public int size() {
        return objects.size();
    }

    // ── OR Operations ─────────────────────────────────────────────────────

    /**
     * Merges another ObjectRepository into this one.  Objects in {@code other}
     * overwrite same-named objects in this OR (last-write wins).
     *
     * <p>Equivalent to UFT's <em>Merge Object Repositories</em> operation.
     *
     * @param other the OR to merge from
     * @return number of objects added or updated
     */
    public int merge(ObjectRepository other) {
        int count = 0;
        for (TestObject obj : other.objects.values()) {
            objects.put(obj.getName(), obj);
            count++;
        }
        return count;
    }

    /**
     * Returns a diff: objects present in {@code other} but not in this OR,
     * and objects present in this OR but not in {@code other}.
     */
    public OrdiffResult diff(ObjectRepository other) {
        List<String> onlyInThis  = new ArrayList<>(objects.keySet());
        List<String> onlyInOther = new ArrayList<>(other.objects.keySet());
        onlyInThis.removeAll(other.objects.keySet());
        onlyInOther.removeAll(objects.keySet());
        List<String> common = objects.keySet().stream()
                .filter(other.objects::containsKey).collect(Collectors.toList());
        return new OrdiffResult(onlyInThis, onlyInOther, common);
    }

    /**
     * Promotes an {@link ElementInfo} from a recording into a named OR entry.
     * Existing object with the same name is replaced.
     */
    public TestObject importFromElementInfo(String name, String objectClass,
                                             ElementInfo ei, String pageUrl) {
        TestObject obj = new TestObject(name, objectClass);
        obj.setPageUrl(pageUrl);
        if (ei.getId()    != null) obj.addLocator(ElementLocator.Strategy.ID,    ei.getId());
        if (ei.getName()  != null) obj.addLocator(ElementLocator.Strategy.NAME,  ei.getName());
        if (ei.getCss()   != null) obj.addLocator(ElementLocator.Strategy.CSS,   ei.getCss());
        if (ei.getXpath() != null) obj.addLocator(ElementLocator.Strategy.XPATH, ei.getXpath());
        if (ei.getText()  != null) obj.getProperties().put("text", ei.getText());
        add(obj);
        return obj;
    }

    // ── Getters / Setters ─────────────────────────────────────────────────

    public String                   getSchemaVersion() { return schemaVersion; }
    public void                     setSchemaVersion(String v) { this.schemaVersion = v; }
    public String                   getDescription()   { return description; }
    public void                     setDescription(String d) { this.description = d; }
    public Map<String, TestObject>  getObjects()       { return objects; }
    public void                     setObjects(Map<String, TestObject> o) { this.objects = o; }

    // ── Inner types ───────────────────────────────────────────────────────

    /** Result of an OR diff operation. */
    public record OrdiffResult(
            List<String> onlyInA,
            List<String> onlyInB,
            List<String> common
    ) {}
}
