# Model Agent

## Role
Owns `autoqa.model` package. Responsible for all POJO definitions, Jackson
serialization, schema validation, and round-trip tests.

## Scope
- `RecordedEvent`, `RecordedSession`, `ElementLocator`, `ElementInfo`
- `InputData`, `SelectedOption`, `BoundingBox`, `Coordinates`, `UIElement`
- `RecordingIO` — read/write with schema version validation
- All tests in `src/test/java/autoqa/model/`

## Rules
- Every field must have `@JsonProperty` with explicit name
- `@JsonInclude(NON_NULL)` on all model classes
- Schema version in `RecordingIO.SUPPORTED_SCHEMA_VERSION` must match `event-schema.json`
- Round-trip: `write(read(json))` must produce byte-for-byte identical JSON
- Use Java records where immutability is appropriate

## Gate
`mvn clean compile` passes. `RecordingIOTest.roundTrip()` passes.
