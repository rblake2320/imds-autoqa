package autoqa.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Jira REST API v2 client for creating bug reports and posting test execution comments.
 *
 * <p>Authentication uses HTTP Basic with a Jira API token (username + API token).
 * All network calls are wrapped in try/catch — failures are logged and the method
 * returns {@code null} or completes silently rather than propagating exceptions.
 */
public class JiraClient {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);
    private static final MediaType JSON = MediaType.parse("application/json");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String username;
    private final String apiToken;
    private final String defaultProjectKey;
    private final OkHttpClient httpClient;

    /**
     * Creates a new JiraClient.
     *
     * @param baseUrl           Jira instance root URL (e.g. {@code https://myorg.atlassian.net})
     * @param username          Jira account email / username
     * @param apiToken          Jira API token
     * @param defaultProjectKey project key used when none is supplied to {@link #createBug}
     */
    public JiraClient(String baseUrl, String username, String apiToken, String defaultProjectKey) {
        this.baseUrl           = baseUrl == null ? "" : baseUrl.stripTrailing();
        this.username          = username == null ? "" : username;
        this.apiToken          = apiToken == null ? "" : apiToken;
        this.defaultProjectKey = defaultProjectKey == null ? "" : defaultProjectKey;
        this.httpClient        = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // ── Factory ───────────────────────────────────────────────────────────

    /**
     * Creates a {@link JiraClient} by reading {@code config.properties} from the classpath.
     *
     * <p>Recognised keys:
     * <ul>
     *   <li>{@code jira.base.url}              — Jira instance URL</li>
     *   <li>{@code jira.username}              — account email</li>
     *   <li>{@code jira.api.token}             — API token</li>
     *   <li>{@code jira.default.project.key}   — default project key (e.g. {@code AUTOQA})</li>
     * </ul>
     *
     * @return a configured {@link JiraClient}; call {@link #isConfigured()} to verify credentials
     */
    public static JiraClient fromConfig() {
        Properties props = loadConfig();
        return new JiraClient(
                props.getProperty("jira.base.url",             ""),
                props.getProperty("jira.username",             ""),
                props.getProperty("jira.api.token",            ""),
                props.getProperty("jira.default.project.key",  "AUTOQA")
        );
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Returns {@code true} only when {@code baseUrl}, {@code username}, and
     * {@code apiToken} are all non-blank.  A {@code false} result means the
     * client cannot make authenticated API calls.
     */
    public boolean isConfigured() {
        return !baseUrl.isBlank() && !username.isBlank() && !apiToken.isBlank();
    }

    /**
     * Creates a Bug issue in the specified Jira project.
     *
     * @param summary     one-line issue title
     * @param description detailed issue body (plain text or wiki markup)
     * @param projectKey  target project key; if {@code null} or blank the default key is used
     * @return the new issue key (e.g. {@code "PROJ-123"}), or {@code null} on any failure
     */
    public String createBug(String summary, String description, String projectKey) {
        if (!isConfigured()) {
            log.warn("JiraClient is not configured — skipping createBug for summary='{}'", summary);
            return null;
        }

        String effectiveProject = (projectKey == null || projectKey.isBlank())
                ? defaultProjectKey : projectKey;

        String url  = baseUrl + "/rest/api/2/issue";
        String body = buildCreateIssueJson(summary, description, effectiveProject);

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
                log.error("Jira createBug failed with HTTP {}: {}", status, responseBody);
                return null;
            }

            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode keyNode = root.path("key");
            if (keyNode.isMissingNode() || keyNode.isNull()) {
                log.error("Jira createBug response missing 'key' field: {}", responseBody);
                return null;
            }

            String issueKey = keyNode.asText();
            log.info("Jira bug created: {} — '{}'", issueKey, summary);
            return issueKey;

        } catch (IOException e) {
            log.error("Jira createBug I/O error for summary='{}': {}", summary, e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("Jira createBug unexpected error for summary='{}': {}", summary, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Appends a comment to an existing Jira issue.
     *
     * @param issueKey issue identifier (e.g. {@code "PROJ-42"})
     * @param comment  comment body text
     */
    public void updateTestExecution(String issueKey, String comment) {
        if (!isConfigured()) {
            log.warn("JiraClient is not configured — skipping updateTestExecution for issue={}", issueKey);
            return;
        }

        String url  = baseUrl + "/rest/api/2/issue/" + issueKey + "/comment";
        String body = buildCommentJson(comment);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", Credentials.basic(username, apiToken))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(RequestBody.create(body, JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            int status = response.code();
            if (!response.isSuccessful()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                log.error("Jira updateTestExecution failed with HTTP {} for issue={}: {}",
                        status, issueKey, responseBody);
            } else {
                log.info("Comment added to Jira issue {}", issueKey);
            }
        } catch (IOException e) {
            log.error("Jira updateTestExecution I/O error for issue={}: {}", issueKey, e.getMessage(), e);
        } catch (Exception e) {
            log.error("Jira updateTestExecution unexpected error for issue={}: {}", issueKey, e.getMessage(), e);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private static String buildCreateIssueJson(String summary, String description, String projectKey) {
        try {
            ObjectNode root   = MAPPER.createObjectNode();
            ObjectNode fields = root.putObject("fields");

            ObjectNode project = fields.putObject("project");
            project.put("key", projectKey);

            fields.put("summary", summary != null ? summary : "");
            fields.put("description", description != null ? description : "");

            ObjectNode issueType = fields.putObject("issuetype");
            issueType.put("name", "Bug");

            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.error("Failed to build Jira issue JSON: {}", e.getMessage(), e);
            return "{}";
        }
    }

    private static String buildCommentJson(String comment) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("body", comment != null ? comment : "");
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.error("Failed to build Jira comment JSON: {}", e.getMessage(), e);
            return "{}";
        }
    }

    private static Properties loadConfig() {
        Properties props = new Properties();
        try (InputStream is = JiraClient.class.getClassLoader().getResourceAsStream("config.properties")) {
            if (is != null) {
                props.load(is);
                log.debug("JiraClient loaded config.properties from classpath");
            } else {
                log.warn("config.properties not found on classpath — Jira client will use empty defaults");
            }
        } catch (IOException e) {
            log.warn("Could not read config.properties: {}", e.getMessage());
        }
        return props;
    }
}
