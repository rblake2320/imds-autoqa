# IMDS AutoQA Wrapper

Air-gapped Selenium recorder/player for Microsoft Edge with local LLM self-healing, comprehensive application spying, network monitoring, mobile emulation, visual regression, and Allure/Jira/Confluence reporting.

**332 unit tests passing. No browser required for the test suite.**

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           WrapperCLI (Picocli)                           │
│   record │ play │ generate │ run │ heal │ report │ keyword │ version     │
└──┬───────┴──┬───┴────┬─────┴──┬──┴──┬──┴───┬────┴─────────┴─────────────┘
   │          │        │        │     │      │
   ▼          ▼        ▼        ▼     ▼      ▼
Recorder   Player   AI Module  Run  Heal  Reporting
  │          │        │              │       │
  ├─CDPConn  ├─LocRes ├─LLMClient    │    AllureListener
  ├─OSCapt   ├─Wait   ├─TestGen      │    JiraClient
  ├─Enrich   ├─Popups ├─Healer       │    ConfluenceClient
  └─Redact   ├─Shadow └─Wandb        │    ReportOrchestrator
             ├─Mobile
             ├─Visual    Network / Spy Layer (passive observers)
             ├─Console   ─────────────────────────────────────
             ├─Smart     NetworkMonitor  ApplicationSpy
             └─Retry     NetworkCapture  SpyCapture
                         NetworkAssert   (XHR/Fetch/Storage/
                                          Console/DOM/Cookies)
```

---

## Module Inventory

| Package | Key Classes | Purpose |
|---|---|---|
| `autoqa.cli` | `WrapperCLI` | Unified CLI entry-point (Picocli) |
| `autoqa.model` | `RecordedSession`, `RecordedEvent`, `RecordingIO`, `RecordingEncryption` | JSON data model, AES-256 encryption |
| `autoqa.player` | `PlayerEngine`, `LocatorResolver`, `WaitStrategy`, `PopupSentinel`, `ActionHandlers`, `FrameNavigator`, `EvidenceCollector`, `ShadowDomHandler`, `SmartWait`, `RetryAnalyzer`, `MobileEmulation`, `VisualRegression`, `ConsoleMonitor`, `PlayerConfig` | Full replay engine |
| `autoqa.recorder` | `CDPConnector`, `DOMEnricher`, `OSInputCapture`, `RecordingSession`, `FieldRedactor`, `RecorderCLI` | OS hook + CDP recording |
| `autoqa.ai` | `LLMClient`, `TestGenerator`, `LocatorHealer`, `HealingInterceptor`, `WandbTraceClient` | LLM test generation + self-healing + W&B Weave tracing |
| `autoqa.vision` | `VisionService`, `StubVisionService`, `NvidiaVisionClient`, `NvClipClient`, `UsdSearchClient` | NVIDIA NIM vision (NV-CLIP, OCR, USD Search) |
| `autoqa.network` | `NetworkMonitor`, `NetworkCapture`, `NetworkAssertion` | CDP network traffic capture and assertions |
| `autoqa.spy` | `ApplicationSpy`, `SpyCapture` | Comprehensive JS-shim app observation layer |
| `autoqa.keyword` | `KeywordEngine`, `KeywordLibrary`, `KeywordStep` | Keyword-driven test execution |
| `autoqa.accessibility` | `AccessibilityScanner`, `AccessibilityRule`, `AccessibilityReport` | WCAG 2.1 accessibility scanning |
| `autoqa.api` | `ApiClient`, `ApiAssertion`, `ApiResponse` | REST API testing without browser |
| `autoqa.data` | `DataProvider`, `DataTable`, `CsvDataSource` | Data-driven test support |
| `autoqa.reporting` | `AllureListener`, `ReportOrchestrator`, `JiraClient`, `ConfluenceClient`, `FailureAnalyzer`, `PerformanceAssertion` | Full reporting pipeline |

---

## Features

### Core Record/Play/Generate
- **Record** — captures mouse, keyboard, navigation, dropdowns, alerts, window switches via JNativeHook + CDP (no browser extension)
- **Play** — deterministic replay with explicit waits, popup sentinel, frame navigation, evidence on failure
- **Generate** — creates compilable Java TestNG files from recordings via local Ollama LLM (air-gapped)
- **Heal** — self-repairs broken locators via LLM prompt + DOM-text-comparison fallback; retry analyzer for flaky tests
- **Encrypt** — AES-256-GCM encryption for recording files with sensitive data

### ApplicationSpy — Comprehensive App Observer
Goes beyond UFT One, TestComplete, and Playwright Inspector with a unified, time-ordered capture stream:

- **Full network capture** — every XHR/Fetch request AND response with headers, full body (configurable size), timing
- **Storage surveillance** — localStorage/sessionStorage reads/writes/deletes, timestamped
- **Cookie monitoring** — document.cookie change detection
- **DOM mutation stream** — every node added/removed via MutationObserver
- **Console interception** — all console.log/warn/error/debug with level tagging
- **JS variable watch** — monitor global variables via Object.defineProperty spy
- **Custom event capture** — subscribe to application-level CustomEvents

Architecture: pure JavaScript shim injected into page (`window.__iqaSpy` queue), polled by a background Java daemon thread every 250ms. Works in any Chromium browser, no CDP version dependency.

```java
ApplicationSpy spy = ApplicationSpy.attach(driver).start();

// ... run test steps ...

spy.assertApiCalled("/api/checkout", "POST")
   .assertApiResponseContains("/api/checkout", "orderId")
   .assertNoServerErrors()
   .assertStorageSet("authToken")
   .assertNoConsoleErrors()
   .assertDomAdded(".modal-dialog");

String report = spy.report();  // Full timeline for Allure attachment
spy.stop();
```

### NetworkMonitor — CDP Network Layer
Passively captures all network traffic at the CDP level:

```java
NetworkMonitor net = NetworkMonitor.attach(driver).start();
// ... test steps ...
net.assertRequested("api/users")
   .assertNoServerErrors()
   .assertResponseBelow("api/search", 500);
```

### MobileEmulation — Device Emulation
Presets: iPhone SE, iPhone 14, iPhone 14 Pro Max, Pixel 7/7 Pro, Samsung S23, iPad Air, iPad Pro 12.9, Galaxy Tab S8, Surface Pro 7.

```java
MobileEmulation.apply(driver, MobileEmulation.Device.IPHONE_14);
driver.get("https://example.com");
// ... mobile test ...
MobileEmulation.reset(driver);
```

### VisualRegression — Pixel-Diff Screenshots
```java
VisualRegression vr = new VisualRegression(driver, "target/baselines");
vr.captureBaselineIfMissing("checkout-page");
// ... after change ...
vr.assertMatchesBaseline("checkout-page", 0.01);  // max 1% diff
```

### ShadowDomHandler — Shadow DOM Support
```java
WebElement el = ShadowDomHandler.find(driver,
    "my-app >>> my-form >>> input[name='email']");
```

### SmartWait — Framework-Aware Waits
```java
SmartWait.forAngular(driver);         // Angular testability hook
SmartWait.forReactIdle(driver);       // React DevTools fiber
SmartWait.forJQuery(driver);          // jQuery.active === 0
SmartWait.forAnimationsComplete(driver); // Web Animations API
SmartWait.forNetworkIdle(driver, 500); // No XHR/Fetch for 500ms
```

### ConsoleMonitor — Browser Console Capture
```java
ConsoleMonitor console = ConsoleMonitor.attach(driver).start();
// ... test ...
console.assertNoErrors();
console.assertContains("Payment processed");
List<ConsoleMessage> errors = console.errors();
```

### NV-CLIP — NVIDIA Semantic Image Comparison
```java
NvClipClient nvclip = NvClipClient.local();  // http://localhost:8000
double score = nvclip.imageTextSimilarity(driver, "login form with username field");
nvclip.assertSemanticMatch(driver, "checkout success page", 0.25);
nvclip.assertVisualMatch(driver, baselineBytes, 0.90);
```

### USD Search — NVIDIA 3D Asset Search
```java
UsdSearchClient usd = new UsdSearchClient(endpoint, apiKey);
List<SearchResult> results = usd.searchByText("car model exterior");
List<SearchResult> hybrid  = usd.hybridSearch("sports car", driver, 0.7, 10);
```

### W&B Weave Tracing — Test Execution Observability
```java
WandbTraceClient tracer = WandbTraceClient.fromConfig();
String traceId = tracer.startTrace("login-test", "autoqa/test", inputs);
// ... test steps ...
tracer.endTrace(traceId, outputs, null);
```

### Keyword-Driven Testing
```
keyword: navigateTo
  url: https://example.com/login

keyword: enterText
  target: id=username
  value: testuser@example.com

keyword: clickElement
  target: id=loginBtn

keyword: assertElementVisible
  target: css=.dashboard-header
```

```bash
autoqa keyword run tests/login.yaml --browser edge
```

---

## Requirements

| Dependency | Version | Notes |
|---|---|---|
| Java | 17+ | Temurin or OpenJDK at `D:/Java/jdk-17.0.18+8` |
| Maven | 3.9+ | At `D:/Maven/apache-maven-3.9.6` |
| Microsoft Edge | Any | With `--remote-debugging-port=9222` for recording |
| Ollama | Latest | `ollama pull qwen2.5-coder:32b` (~19GB) |
| Allure CLI | 2.x | For HTML report generation |
| NVIDIA NIM | Optional | NV-CLIP at `http://localhost:8000`, USD Search |
| W&B Weave | Optional | Trace logging to `https://trace.wandb.ai` |

---

## Quick Start

### 1. Build

```bash
JAVA_HOME=D:/Java/jdk-17.0.18+8 D:/Maven/apache-maven-3.9.6/bin/mvn.cmd clean package -DskipTests
```

The fat JAR is at `target/imds-autoqa-1.0.0-SNAPSHOT.jar`.

### 2. Record a session

Start Edge with remote debugging:

```bash
"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" --remote-debugging-port=9222
```

Then record:

```bash
java -jar target/imds-autoqa-1.0.0-SNAPSHOT.jar record start
# Interact with Edge...
# Press Ctrl+C to stop — recording saves to recordings/
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

### 5. Run keyword test

```bash
java -jar target/imds-autoqa-1.0.0-SNAPSHOT.jar keyword run tests/login.yaml
```

### 6. View the Allure report

```bash
allure serve target/allure-results
```

---

## Configuration (`src/main/resources/config.properties`)

### Recorder
| Key | Default | Description |
|---|---|---|
| `recorder.cdp.port` | `9222` | Edge remote debug port |
| `recorder.output.dir` | `recordings` | Recording save directory |
| `recorder.url.whitelist` | *(blank = all)* | Only record on these URL substrings |

### Player
| Key | Default | Description |
|---|---|---|
| `player.implicit.wait.ms` | `0` | Always 0; use explicit waits only |
| `player.page.load.timeout.sec` | `30` | Page load timeout |
| `player.evidence.dir` | `evidence` | Screenshot/source dump on failure |

### Retry
| Key | Default | Description |
|---|---|---|
| `retry.max.attempts` | `3` | Max retries for flaky tests |
| `retry.delay.ms` | `500` | Base delay; multiplied by retry count |

### AI / LLM
| Key | Default | Description |
|---|---|---|
| `ai.base.url` | `http://localhost:11434/v1` | Ollama OpenAI-compatible endpoint |
| `ai.model` | `qwen2.5-coder:32b` | LLM for test generation and healing |
| `ai.timeout.ms` | `120000` | LLM call timeout |

### Vision / NVIDIA NIM
| Key | Default | Description |
|---|---|---|
| `vision.enabled` | `false` | Enable NVIDIA NIM vision enrichment |
| `vision.endpoint` | `http://localhost:8000` | NIM inference endpoint |
| `nvclip.endpoint` | `http://localhost:8000/v1/embeddings` | NV-CLIP embedding endpoint |
| `usd.search.endpoint` | *(blank)* | USD Search API endpoint |

### ApplicationSpy
| Key | Default | Description |
|---|---|---|
| `spy.poll.ms` | `250` | JS queue drain interval |
| `spy.max.body.chars` | `4096` | Max request/response body chars captured |

### W&B Weave
| Key | Default | Description |
|---|---|---|
| `wandb.enabled` | `false` | Enable Weave trace logging |
| `wandb.api.key` | *(blank)* | W&B API key |
| `wandb.project` | `imds-autoqa` | W&B project name |

### Reporting
| Key | Default | Description |
|---|---|---|
| `jira.base.url` | *(blank)* | Jira Cloud base URL |
| `jira.username` | *(blank)* | Jira username (email) |
| `jira.api.token` | *(blank)* | Jira API token |
| `confluence.base.url` | *(blank)* | Confluence Cloud base URL |

---

## CLI Reference

```
autoqa --help

Commands:
  record            Record user interactions in Microsoft Edge
    start           Start recording (Ctrl+C to stop and save)
    stop            Informational — send Ctrl+C to the recording process
    list            List saved recordings and event counts

  play              Replay a saved recording in Edge
    <file>          Path to recording JSON

  generate          Generate Java TestNG test from recording via LLM
    <file>          Path to recording JSON
    --model         Override default LLM model

  run               Run the TestNG regression suite
    --suite         TestNG suite XML (default: regression.xml)
    --serve         Auto-serve Allure report after run

  heal              Demonstrate LLM locator healing on a recording
    <file>          Path to recording JSON

  report            Generate Allure HTML report
    --serve         Open in browser immediately

  keyword           Keyword-driven test execution
    run <file>      Run keyword test YAML file
      --browser     Browser to use (default: edge)
      --or-file     Object repository YAML

  version           Print version and module information
```

---

## Locator Resolution Order

The player tries locators in priority order:

1. `id` attribute
2. `name` attribute
3. CSS selector
4. XPath
5. Shadow DOM path (`>>>` chaining)
6. LLM-healed locator (on `NoSuchElementException`, one retry)

---

## Self-Healing Flow

1. Locator fails → `HealingInterceptor` catches `NoSuchElementException`
2. Captures current page source
3. Builds LLM prompt: failed locator + page source snippet + URL
4. LLM returns candidate locator
5. If LLM returns `CANNOT_HEAL` → falls back to DOM text-comparison XPath
6. Retries once with healed locator
7. Healing attempt logged to `logs/healing.log`
8. If `wandb.enabled=true` → healing span logged to W&B Weave for model feedback loop

---

## Project Structure

```
src/
  main/java/autoqa/
    cli/           WrapperCLI.java
    model/         RecordedEvent, RecordedSession, RecordingIO, RecordingEncryption,
                   ElementLocator, ElementInfo, InputData, BoundingBox, Coordinates,
                   UIElement, SelectedOption
    player/        PlayerEngine, LocatorResolver, WaitStrategy, PopupSentinel,
                   ActionHandlers (Click/Key/Input/Select/Scroll/Navigate/Alert/Window),
                   FrameNavigator, EvidenceCollector, PlayerConfig,
                   ShadowDomHandler, SmartWait, RetryAnalyzer,
                   MobileEmulation, VisualRegression, ConsoleMonitor
    recorder/      CDPConnector, DOMEnricher, OSInputCapture, RecordingSession,
                   FieldRedactor, RecorderCLI
    ai/            LLMClient, TestGenerator, LocatorHealer, HealingInterceptor,
                   WandbTraceClient
    vision/        VisionService, StubVisionService, NvidiaVisionClient,
                   NvClipClient, UsdSearchClient
    network/       NetworkMonitor, NetworkCapture, NetworkAssertion
    spy/           ApplicationSpy, SpyCapture
    keyword/       KeywordEngine, KeywordLibrary, KeywordStep
    accessibility/ AccessibilityScanner, AccessibilityRule, AccessibilityReport
    api/           ApiClient, ApiAssertion, ApiResponse
    data/          DataProvider, DataTable, CsvDataSource
    reporting/     AllureListener, ReportOrchestrator, JiraClient, ConfluenceClient,
                   FailureAnalyzer, PerformanceAssertion

  test/java/autoqa/   (mirrors main, 332 unit tests — no browser required)
  main/resources/
    config.properties
    event-schema.json
    logback.xml
  test/resources/
    test-recording.json
    test-page.html
```

---

## Running Tests

```bash
# All tests (332 passing)
JAVA_HOME=D:/Java/jdk-17.0.18+8 D:/Maven/apache-maven-3.9.6/bin/mvn.cmd test

# Specific suite
JAVA_HOME=D:/Java/jdk-17.0.18+8 D:/Maven/apache-maven-3.9.6/bin/mvn.cmd test \
  -Dsurefire.suiteXmlFiles=regression.xml

# Single test class
JAVA_HOME=D:/Java/jdk-17.0.18+8 D:/Maven/apache-maven-3.9.6/bin/mvn.cmd test \
  -Dtest=ApplicationSpyTest
```

Unit tests require no running Edge browser, Ollama, or NVIDIA NIM — all external I/O is isolated (stub constructors, mock data, in-memory capture lists).

---

## Integration Points

### NVIDIA AI Ecosystem
- **NV-CLIP 2.0.0** (`nvcr.io/nim/nvidia/nvclip:2.0.0`) — semantic screenshot comparison, image-text similarity
- **USD Search API 1.2** — hybrid text+vector search over 3D USD assets (Omniverse test enrichment)
- **NVIDIA NIM Vision** — OCR and object detection for robust element location

### W&B Weave (Weights & Biases)
- Traces every test execution as a span tree in `https://trace.wandb.ai`
- Records LLM calls (test generation, locator healing) with inputs/outputs for model evaluation
- Enables data flywheel: failed heals → labeled training examples

### Allure + Jira + Confluence
- `AllureListener` attaches screenshots, spy reports, console logs, and network timelines to every failed test
- `JiraClient` creates bugs automatically for test failures with Allure deep-link
- `ConfluenceClient` publishes run summary page per suite execution

---

## Security Notes

- Passwords and configurable patterns are redacted to `[REDACTED]` before saving
- Recording files can be AES-256-GCM encrypted via `RecordingEncryption`
- No credentials committed to version control — use environment variables
- JNativeHook captures OS input only while recording is active and process is in foreground
- ApplicationSpy JS shim is read-only — it wraps APIs but does not modify application data

---

## New Session Quickstart (for Claude Code)

Key file paths for continuing work:
- **Project root**: `D:/imds-autoqa/`
- **JDK**: `D:/Java/jdk-17.0.18+8`
- **Maven**: `D:/Maven/apache-maven-3.9.6`
- **Test suite**: `D:/imds-autoqa/regression.xml` (12 suites, 332 tests)
- **Main CLI**: `D:/imds-autoqa/src/main/java/autoqa/cli/WrapperCLI.java`
- **Config**: `D:/imds-autoqa/src/main/resources/config.properties`
- **Build command**: `JAVA_HOME="D:/Java/jdk-17.0.18+8" D:/Maven/apache-maven-3.9.6/bin/mvn.cmd -f D:/imds-autoqa/pom.xml test`
- **GitHub**: `https://github.com/rblake2320/imds-autoqa`

Current test count: **332 passing** (as of 2026-02-27)

---

## License

MIT
