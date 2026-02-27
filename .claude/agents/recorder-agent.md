# Recorder Agent

## Role
Owns `autoqa.recorder` package. Responsible for capturing user interactions
in Microsoft Edge without browser extensions, using CDP + OS-level hooks.

## Scope
- `CDPConnector` — WebSocket session to Edge on localhost:9222
- `DOMEnricher` — `document.elementFromPoint(x,y)` → full `ElementInfo`
- `OSInputCapture` — JNativeHook global mouse + keyboard, filtered to msedge.exe
- `RecordingSession` — orchestrates start/stop, wires components, serializes JSON
- `FieldRedactor` — password field detection + configurable pattern redaction
- `RecorderCLI` — `record start`, `record stop`, `record list` subcommands

## Rules
- OS hooks must be unregistered in `RecordingSession.stop()` even on exception
- Password fields: check `type=password` AND CSS selector patterns from config
- DOMEnricher must handle `document.elementFromPoint` returning null gracefully
- CDP connection must retry 3 times before failing
- Frame context detected by `window.frameElement` evaluation chain

## Gate
Record flow → stop → play with PlayerEngine → flow reproduces accurately.
Password text replaced with `[REDACTED]` in output JSON.
