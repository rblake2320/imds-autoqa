# IMDS AutoQA Wrapper

Air-gapped Selenium recorder/player for Microsoft Edge with local LLM self-healing and Allure/Jira/Confluence reporting.

## Features

- **Record** — captures mouse clicks, keyboard input, navigation, dropdowns, alerts, and window switches via JNativeHook OS hooks + Chrome DevTools Protocol (no browser extension required)
- **Play** — deterministic replay via Selenium WebDriver 4 with explicit waits, popup detection, and evidence collection on failure
- **Generate** — creates ready-to-compile Java TestNG test files from recordings using a local Ollama LLM (qwen2.5-coder:32b, air-gapped)
- **Heal** — self-repairs broken locators via LLM prompt + DOM-comparison fallback (Healenium-inspired)
- **Report** — Allure HTML reports; optional Jira bug creation and Confluence page publishing per test run
- **Encrypt** — AES-256-GCM encryption for recording files containing sensitive data

## Requirements

| Dependency | Version | Notes |
|---|---|---|
| Java | 17+ | Temurin or OpenJDK |
| Maven | 3.9+ | |
| Microsoft Edge | Any | With `--remote-debugging-port=9222` |
| Ollama | Latest | `ollama pull qwen2.5-coder:32b` |
| Allure CLI | 2.x | For HTML report generation |

## Quick Start

### 1. Build

```bash
mvn clean package -DskipTests
```

The fat JAR is at `target/imds-autoqa-1.0.0-SNAPSHOT.jar`.

### 2. Record a session

Start Edge with remote debugging:

```bash
# Windows
"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" --remote-debugging-port=9222
```

Then record:

```bash
java -jar target/imds-autoqa-1.0.0-SNAPSHOT.jar record start
# Interact with Edge...
# Press Ctrl+C to stop — recording saves automatically to recordings/
```

### 3. Play back

```bash
java -jar target/imds-autoqa-1.0.0-SNAPSHOT.jar play recordings/recording-001.json
```

### 4. Generate a TestNG test

Ensure Ollama is running (`ollama serve`), then:

```bash
java -jar target/imds-autoqa-1.0.0-SNAPSHOT.jar generate recordings/recording-001.json
# Output: generated-tests/AutoQATest_<id>.java
```

Copy the generated file to `src/test/java/generated/` and run:

```bash
mvn test
```

### 5. View the Allure report

```bash
allure serve target/allure-results
```

Or:

```bash
java -jar target/imds-autoqa-1.0.0-SNAPSHOT.jar report --serve
```

## Configuration

All settings are in `src/main/resources/config.properties`. No secrets in the file — credentials are referenced as environment variable names.

| Key | Default | Description |
|---|---|---|
| `recorder.cdp.port` | `9222` | Edge remote debug port |
| `recorder.output.dir` | `recordings` | Where recordings are saved |
| `recorder.url.whitelist` | *(blank = all)* | Only record on these URL substrings |
| `player.implicit.wait.ms` | `0` | Always 0; use explicit waits |
| `player.page.load.timeout.sec` | `30` | Page load timeout |
| `player.evidence.dir` | `evidence` | Evidence directory on failure |
| `ai.base.url` | `http://localhost:11434/v1` | Ollama OpenAI-compatible endpoint |
| `ai.model` | `qwen2.5-coder:32b` | LLM model for test generation and healing |
| `ai.timeout.ms` | `120000` | LLM call timeout (2 min) |
| `vision.enabled` | `false` | Enable NVIDIA NIM vision enrichment |
| `jira.base.url` | *(blank)* | Jira Cloud base URL |
| `jira.username` | *(blank)* | Jira username (email) |
| `jira.api.token` | *(blank)* | Jira API token |
| `confluence.base.url` | *(blank)* | Confluence Cloud base URL |

## Project Structure

```
src/
  main/java/
    autoqa/
      cli/        WrapperCLI.java          — unified entry-point
      model/      RecordedEvent, Session, RecordingIO, RecordingEncryption
      player/     PlayerEngine, ActionHandlers, LocatorResolver, WaitStrategy
      recorder/   CDPConnector, DOMEnricher, OSInputCapture, FieldRedactor
      ai/         LLMClient, TestGenerator, LocatorHealer, HealingInterceptor
      vision/     VisionService, NvidiaVisionClient
      reporting/  AllureListener, ReportOrchestrator, JiraClient, ConfluenceClient
  test/java/      (mirrors main, one test class per main class)
  main/resources/
    config.properties
    event-schema.json
    logback.xml
```

## CLI Reference

```
autoqa --help

Commands:
  record    Record user interactions in Microsoft Edge
    start   Start recording (Ctrl+C to stop and save)
    stop    Informational — send Ctrl+C to the recording process
    list    List saved recordings and their event counts

  play      Replay a saved recording in Edge

  generate  Generate a Java TestNG test from a recording via local LLM

  run       Run the TestNG regression suite and generate Allure report

  heal      Demonstrate LLM locator healing on a recording

  report    Generate Allure HTML report from target/allure-results

  version   Print version information
```

## Locator Resolution Order

The player tries locators in this priority order (per CLAUDE.md rule):

1. `id` attribute
2. `name` attribute
3. CSS selector
4. XPath
5. LLM-healed locator (on failure, one retry)

## Self-Healing Flow

1. Locator fails → `HealingInterceptor` catches `NoSuchElementException`
2. Captures current page source
3. Builds LLM prompt: failed locator + page source snippet + URL
4. LLM returns candidate locator
5. If LLM returns `CANNOT_HEAL`: falls back to DOM text-comparison XPath
6. Retries once with healed locator
7. Healing attempt logged to `logs/healing.log`

## Security Notes

- Passwords and fields matching configurable patterns are redacted to `[REDACTED]` before saving
- Recording files can be AES-256-GCM encrypted via `RecordingEncryption`
- No credentials committed to version control — use environment variables or key files
- JNativeHook captures global OS input only while recording is active

## Running Tests

```bash
mvn test
# Or with specific suite:
mvn test -Dsurefire.suiteXmlFiles=regression.xml
```

Unit tests require no running Edge browser or Ollama — all external I/O is mocked with WireMock and Mockito.

## License

MIT
