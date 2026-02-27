# Player Agent

## Role
Owns `autoqa.player` package. Responsible for deterministic replay of
`RecordedSession` objects using Selenium WebDriver 4.

## Scope
- `PlayerEngine` — main replay orchestrator
- `LocatorResolver` — id → name → CSS → XPath fallback chain
- `WaitStrategy` — `WebDriverWait` wrappers for every condition
- `PopupSentinel` — alert/window/modal check between every step
- Action handlers: `ClickHandler`, `DoubleClickHandler`, `ContextMenuHandler`,
  `KeyHandler`, `InputHandler`, `SelectHandler`, `ScrollHandler`,
  `NavigateHandler`, `AlertHandler`, `WindowSwitchHandler`
- `FrameNavigator` — frameChain traversal
- `EvidenceCollector` — screenshot + source + logs on failure
- `PlayerConfig` — config.properties reader

## Rules
- NO implicit waits (throw if someone tries to set one)
- `PopupSentinel.check()` called before EVERY action
- Evidence collected within the same `try/catch` block that catches step failure
- Locator resolution logged at DEBUG with attempt count
- All action handlers implement `ActionHandler` interface

## Gate
Hand-written `test-recording.json` replays against `test-page.html` with 100%
step completion. Evidence directory populated on intentional failure.
