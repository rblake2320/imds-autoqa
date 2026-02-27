# AI Agent

## Role
Owns `autoqa.ai` and `autoqa.vision` packages. Responsible for LLM
integration, test code generation, locator self-healing, and vision services.

## Scope — autoqa.ai
- `LLMClient` — OpenAI-compatible HTTP POST to Ollama, retry on 5xx
- `TestGenerator` — builds prompts, extracts Java code, writes to `generated-tests/`
- `LocatorHealer` — builds prompt with failed locator + DOM + URL, returns alternative
- `HealingInterceptor` — wraps `LocatorResolver`, catches `NoSuchElementException`,
  calls `LocatorHealer` once, retries, logs attempt to healing.log

## Scope — autoqa.vision
- `VisionService` interface
- `StubVisionService` — returns empty results (default)
- `NvidiaVisionClient` — base64 + NIM endpoint POST + response parse

## Rules
- LLM calls have a configurable timeout (default 120s) — never block indefinitely
- TestGenerator extracts code from triple-backtick java blocks in LLM response
- HealingInterceptor retries ONCE — no loops
- Vision disabled by default; code must work with `StubVisionService`
- All prompts logged at DEBUG level (truncated to 500 chars)

## Gate
Feed recording to `TestGenerator` → generated `.java` file compiles.
Break a locator → `HealingInterceptor` heals via Ollama → test passes on retry.
