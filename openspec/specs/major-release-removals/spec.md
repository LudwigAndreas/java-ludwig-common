# major-release-removals Specification

## Purpose
States what a major release leaves behind: no published member whose only reason to exist is
compatibility with an earlier release, and a written, gate-checked justification for every removal.
## Requirements
### Requirement: A major release carries no compatibility-only member
A major release SHALL NOT publish a type, constructor, method, field, configuration property or wire
header whose only stated reason to exist is compatibility with an earlier release. For 3.0.0 that removes
`MessageHeaders.LEGACY_*`, the pre-prefix header write and read, `ODataFilterExceptionHandler` and its
property, the non-canonical constructors of `LogFieldNames` and `JsonLogEncoderConfig`, and
`Redaction.LEGACY_SETTINGS_MASK` and `Redaction.LEGACY_HEADER_MASK`.

#### Scenario: A consumer compiles against a removed member
- **WHEN** code built against 2.x references `MessageHeaders.LEGACY_EVENT_TYPE`,
  `ODataFilterExceptionHandler`, `Redaction.LEGACY_HEADER_MASK` or the nineteen-argument
  `LogFieldNames` constructor
- **THEN** it fails to compile against 3.0.0, and the 3.0.0 changelog names the replacement

#### Scenario: A value is tested against the redaction marker
- **WHEN** `Redaction.isMask` is called with `[redacted]` or `****`
- **THEN** it returns `false`; only `Redaction.MASK` is a marker

### Requirement: Every removal is a justified revapi difference
Each element removed from a published module SHALL have its own `revapi.differences` entry in the root
POM whose justification names the change that removed it, the owner's decision and the replacement.

#### Scenario: Each entry matches exactly the difference it records
- **WHEN** a module this change touches is built as a patch increment of its baseline (`2.0.1-SNAPSHOT`
  against `2.0.0`), where the semver rule permits no breaking difference
- **THEN** none of the removed elements is reported, because each entry's code and signature match its
  difference

#### Scenario: A major increment does not make the entry optional
- **WHEN** the same removal is built as `3.0.0` against `2.0.0`
- **THEN** revapi's semver rule accepts every breaking difference without an entry, so the entry is the
  release's written record rather than something the gate enforces, and the requirement holds by review

