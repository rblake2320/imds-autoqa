# IMDS AutoQA — Project Constitution

## Project Overview
Air-gapped Java test automation wrapper for Microsoft Edge.
Records user actions via CDP + OS hooks, replays via Selenium WebDriver 4,
self-heals broken locators using a local Ollama LLM (qwen2.5-coder:32b),
and reports via Allure/Jira/Confluence.

## Architecture
```
autoqa.model       — POJOs: RecordedEvent, RecordedSession, ElementInfo, …
autoqa.recorder    — CDP connector, OS hooks, DOM enricher, session serializer
autoqa.player      — Replay engine, locator resolver, action handlers, evidence
autoqa.ai          — LLM client, test generator, locator healer, interceptor
autoqa.vision      — NVIDIA NIM vision service (optional, stub by default)
autoqa.reporting   — Allure listener, Jira client, Confluence client
autoqa.cli         — Unified CLI: autoqa record | play | generate | run | heal | report
```

## Non-Negotiable Rules

### 1. No Hardcoded Secrets
All secrets (API keys, passwords) are read from environment variables named in config.properties.
Never commit credentials. The config template shows env var names only.

### 2. Locator Priority Order
ALWAYS resolve locators in this order:
1. `id` attribute
2. `name` attribute
3. CSS selector
4. XPath

Never skip to a lower-priority locator without logging that higher ones were tried.

### 3. Explicit Waits Only
`driver.manage().timeouts().implicitlyWait()` is **FORBIDDEN**.
Every interaction must use `WebDriverWait` with `ExpectedConditions`.
Set implicit wait to zero in PlayerConfig.

### 4. PopupSentinel Runs Between Every Step
Before executing any action, `PopupSentinel.check()` must be called.
It checks for: JS alerts, new windows, DOM modal overlays.

### 5. Evidence on Failure
On any step failure: capture screenshot + page source + console logs + URL.
Store in `evidence/{sessionId}/{stepIndex}/`.

### 6. Schema Validation on Load
`RecordingIO.read()` must validate against `event-schema.json` before returning.
Reject sessions with schema version mismatches.

### 7. Vision is Optional
`vision.enabled=false` is the default. The entire system works without it.
Vision enriches locators and popup detection but is never required.

### 8. Test Isolation
Each test run gets a clean WebDriver instance.
Cookie clearing, window sizing, and cache clearing happen in @BeforeMethod.

### 9. AI Healing is One Retry
`HealingInterceptor` retries a failed locator **once** via LLM.
If the healed locator also fails, throw with full context logged.
Do not loop infinitely.

### 10. Recording Redaction
Password fields are always redacted regardless of config.
`inputData.keys` is replaced with `[REDACTED]` for sensitive fields.

## Package Naming
All production classes: `autoqa.<module>.<ClassName>`
All test classes: `autoqa.<module>.<ClassName>Test`

## Coding Standards
- Java 17 features allowed (records, text blocks, switch expressions)
- SLF4J logging (never `System.out.println`)
- Checked exceptions wrapped in unchecked `AutoQAException`
- Jackson `@JsonProperty` on all POJO fields
- `@Nonnull`/`@Nullable` annotations from javax.annotation

## File Locations
- Recordings: `recordings/` (gitignored, local only)
- Generated tests: `generated-tests/` (gitignored, review before committing)
- Evidence: `evidence/` (gitignored)
- Allure results: `target/allure-results/`

## Build
```bash
export JAVA_HOME=/d/Java/jdk-17.0.18+8
export M2_HOME=/d/Maven/apache-maven-3.9.6
export PATH=$JAVA_HOME/bin:$M2_HOME/bin:$PATH
mvn clean compile          # Compile only
mvn clean test             # Compile + run unit tests
mvn clean package          # Fat JAR at target/imds-autoqa-1.0.0-SNAPSHOT.jar
allure serve target/allure-results   # View report
```

## CLI Usage
```bash
java -jar target/imds-autoqa-1.0.0-SNAPSHOT.jar record
java -jar target/imds-autoqa-1.0.0-SNAPSHOT.jar play  recordings/recording-001.json
java -jar target/imds-autoqa-1.0.0-SNAPSHOT.jar generate recordings/recording-001.json
java -jar target/imds-autoqa-1.0.0-SNAPSHOT.jar run
java -jar target/imds-autoqa-1.0.0-SNAPSHOT.jar heal  recordings/recording-001.json
java -jar target/imds-autoqa-1.0.0-SNAPSHOT.jar report
```

## Sub-Agent Roles
See `.claude/agents/` for specialized agent definitions:
- `model-agent.md`   — Owns autoqa.model
- `player-agent.md`  — Owns autoqa.player
- `recorder-agent.md`— Owns autoqa.recorder
- `ai-agent.md`      — Owns autoqa.ai + autoqa.vision
