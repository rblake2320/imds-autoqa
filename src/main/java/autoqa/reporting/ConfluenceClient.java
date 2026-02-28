package autoqa.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Confluence Cloud REST API client for creating and updating wiki pages.
 *
 * <p>Pages are identified by {@code (spaceKey, title)} — if a page with the given
 * title already exists in the space it is updated (version incremented); otherwise
 * a new page is created.  All HTTP errors and I/O exceptions are caught and logged;
 * the method never throws to callers.
 */
public class ConfluenceClient {

    private static final Logger log = LoggerFactory.getLogger(ConfluenceClient.class);
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String username;
    private final String apiToken;
    private final String defaultSpaceKey;
    private final String parentPageId;
    private final OkHttpClient httpClient;

    /**
     * Creates a new ConfluenceClient.
     *
     * @param baseUrl        Confluence Cloud root URL (e.g. {@code https://myorg.atlassian.net})
     * @param username       Atlassian account email
     * @param apiToken       Atlassian API token
     * @param defaultSpaceKey space key used when none is supplied to {@link #createOrUpdatePage}
     * @param parentPageId   optional numeric page ID; pages will be created as children of this page
     */
    public ConfluenceClient(String baseUrl, String username, String apiToken,
                            String defaultSpaceKey, String parentPageId) {
        this.baseUrl        = baseUrl == null ? "" : baseUrl.stripTrailing();
        this.username       = username == null ? "" : username;
        this.apiToken       = apiToken == null ? "" : apiToken;
        this.defaultSpaceKey = defaultSpaceKey == null ? "" : defaultSpaceKey;
        this.parentPageId   = parentPageId == null ? "" : parentPageId;
        this.httpClient     = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // ── Factory ───────────────────────────────────────────────────────────

    /**
     * Creates a {@link ConfluenceClient} from {@code config.properties} on the classpath.
     *
     * <p>Recognised keys:
     * <ul>
     *   <li>{@code confluence.base.url}       — Confluence Cloud root URL</li>
     *   <li>{@code confluence.username}       — Atlassian account email</li>
     *   <li>{@code confluence.api.token}      — API token</li>
     *   <li>{@code confluence.space.key}      — target space key</li>
     *   <li>{@code confluence.parent.page.id} — optional parent page numeric ID</li>
     * </ul>
     *
     * @return a configured {@link ConfluenceClient}; call {@link #isConfigured()} to verify credentials
     */
    public static ConfluenceClient fromConfig() {
        Properties props = loadConfig();
        return new ConfluenceClient(
                props.getProperty("confluence.base.url",       ""),
                props.getProperty("confluence.username",       ""),
                props.getProperty("confluence.api.token",      ""),
                props.getProperty("confluence.space.key",      ""),
                props.getProperty("confluence.parent.page.id", "")
        );
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Returns {@code true} only when {@code baseUrl}, {@code username}, and
     * {@code apiToken} are all non-blank.
     */
    public boolean isConfigured() {
        return !baseUrl.isBlank() && !username.isBlank() && !apiToken.isBlank();
    }

    /**
     * Creates a new Confluence page, or updates the existing page with the same title.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>GET the content search endpoint to check whether a page with {@code title}
     *       already exists in the target space.</li>
     *   <li>If found — PUT an updated version (version.number incremented by 1).</li>
     *   <li>If not found — POST a new page, optionally parented under {@code parentPageId}.</li>
     * </ol>
     *
     * @param title        page title (must be unique within the space)
     * @param htmlContent  Confluence Storage Format (XHTML) page body
     * @param spaceKey     target space key; if {@code null} or blank the configured default is used
     * @param parentPageId ID of the parent page; if {@code null} or blank falls back to the
     *                     configured default; if still blank the page is created at the space root
     */
    public void createOrUpdatePage(String title, String htmlContent,
                                   String spaceKey, String parentPageId) {
        if (!isConfigured()) {
            log.warn("ConfluenceClient is not configured — skipping createOrUpdatePage for '{}'", title);
            return;
        }

        String effectiveSpace  = (spaceKey == null || spaceKey.isBlank())
                ? defaultSpaceKey : spaceKey;
        String effectiveParent = (parentPageId == null || parentPageId.isBlank())
                ? this.parentPageId : parentPageId;

        try {
            ExistingPage existing = findPage(title, effectiveSpace);
            if (existing != null) {
                updatePage(existing.id(), existing.version() + 1, title, htmlContent, effectiveSpace);
            } else {
                createPage(title, htmlContent, effectiveSpace, effectiveParent);
            }
        } catch (Exception e) {
            log.error("ConfluenceClient createOrUpdatePage error for '{}': {}", title, e.getMessage(), e);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Looks up a page by title within a space.
     *
     * @return an {@link ExistingPage} with id and current version, or {@code null} if not found
     */
    private ExistingPage findPage(String title, String spaceKey) throws IOException {
        String encodedTitle = URLEncoder.encode(title, StandardCharsets.UTF_8);
        String url = baseUrl + "/wiki/rest/api/content"
                + "?spaceKey=" + URLEncoder.encode(spaceKey, StandardCharsets.UTF_8)
                + "&title=" + encodedTitle
                + "&expand=version";

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(username, apiToken))
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            int status = response.code();
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("Confluence findPage failed with HTTP {} for title='{}': {}", status, title, body);
                return null;
            }

            JsonNode root    = MAPPER.readTree(body);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                log.debug("No existing Confluence page found for title='{}' in space='{}'", title, spaceKey);
                return null;
            }

            JsonNode first   = results.get(0);
            String   pageId  = first.path("id").asText();
            int      version = first.path("version").path("number").asInt(1);
            log.debug("Found existing Confluence page id={} version={} for title='{}'", pageId, version, title);
            return new ExistingPage(pageId, version);
        }
    }

    private void createPage(String title, String htmlContent,
                            String spaceKey, String effectiveParent) throws IOException {
        String url  = baseUrl + "/wiki/rest/api/content";
        String body = buildPageJson(null, 1, title, htmlContent, spaceKey, effectiveParent);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(username, apiToken))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(RequestBody.create(body, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            int status = response.code();
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("Confluence createPage failed with HTTP {} for title='{}': {}",
                        status, title, responseBody);
            } else {
                String newId = MAPPER.readTree(responseBody).path("id").asText("<unknown>");
                log.info("Confluence page created: id={} title='{}'", newId, title);
            }
        }
    }

    private void updatePage(String pageId, int newVersion, String title,
                            String htmlContent, String spaceKey) throws IOException {
        String url  = baseUrl + "/wiki/rest/api/content/" + pageId;
        String body = buildPageJson(pageId, newVersion, title, htmlContent, spaceKey, null);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(username, apiToken))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .put(RequestBody.create(body, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            int status = response.code();
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.error("Confluence updatePage failed with HTTP {} for id={} title='{}': {}",
                        status, pageId, title, responseBody);
            } else {
                log.info("Confluence page updated: id={} version={} title='{}'", pageId, newVersion, title);
            }
        }
    }

    /**
     * Builds the JSON body for a Confluence create or update request.
     *
     * @param pageId       existing page id (used for updates — may be {@code null} for creates)
     * @param version      version number to set in the payload
     * @param title        page title
     * @param htmlContent  Storage-format HTML body
     * @param spaceKey     target space key
     * @param parentPageId parent page id (optional; ignored when {@code null} or blank)
     */
    private static String buildPageJson(String pageId, int version, String title,
                                        String htmlContent, String spaceKey,
                                        String parentPageId) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();

        if (pageId != null && !pageId.isBlank()) {
            root.put("id", pageId);
        }

        root.put("type", "page");
        root.put("title", title != null ? title : "");

        // Space
        ObjectNode space = root.putObject("space");
        space.put("key", spaceKey != null ? spaceKey : "");

        // Body
        ObjectNode body    = root.putObject("body");
        ObjectNode storage = body.putObject("storage");
        storage.put("value", htmlContent != null ? htmlContent : "");
        storage.put("representation", "storage");

        // Version (required for updates; harmless for creates)
        ObjectNode versionNode = root.putObject("version");
        versionNode.put("number", version);

        // Ancestors (parent page) — only add for creates (pageId == null)
        if ((pageId == null || pageId.isBlank())
                && parentPageId != null && !parentPageId.isBlank()) {
            ArrayNode ancestors = root.putArray("ancestors");
            ObjectNode ancestor = ancestors.addObject();
            ancestor.put("id", parentPageId);
        }

        return MAPPER.writeValueAsString(root);
    }

    private static Properties loadConfig() {
        Properties props = new Properties();
        try (InputStream is = ConfluenceClient.class.getClassLoader()
                .getResourceAsStream("config.properties")) {
            if (is != null) {
                props.load(is);
                log.debug("ConfluenceClient loaded config.properties from classpath");
            } else {
                log.warn("config.properties not found on classpath — Confluence client will use empty defaults");
            }
        } catch (IOException e) {
            log.warn("Could not read config.properties: {}", e.getMessage());
        }
        return props;
    }

    // ── Internal record ───────────────────────────────────────────────────

    /** Holds the id and current version of an existing Confluence page. */
    private record ExistingPage(String id, int version) {}
}
