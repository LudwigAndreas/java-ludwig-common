# The enforcement triad

## Purpose
Three tools check this codebase, and they must not overlap. Two tools checking one rule means two
places to change it, two error messages for one mistake, and eventually two rules that disagree.

## Requirements

### Requirement: Each class of check has exactly one owner
A check SHALL belong to exactly one of three tools:

| Tool | Owns |
|---|---|
| `architecture-rules` (ArchUnit) | structure and dependencies |
| `checkstyle-rules` | source text |
| SonarQube | bugs and security |

#### Scenario: A new rule is about a type's name, package or dependencies
- **WHEN** a change needs to forbid a shape that is visible in bytecode — a second audit SPI, a
  module-local status enum, a dependency in the wrong direction
- **THEN** the rule goes in `architecture-rules` as an ArchUnit rule in the appropriate
  `RuleGroup`, and not in Checkstyle

#### Scenario: A new rule is about the text of the source
- **WHEN** a change needs to forbid something that is a fact about source text rather than about
  structure
- **THEN** the rule goes in `checkstyle-rules`, and not in ArchUnit

#### Scenario: The rule is about a constant's value
- **WHEN** the rule concerns the *value* of a string constant, as the second-redaction-mask rule
  does
- **THEN** it must be a Checkstyle rule, because ArchUnit reads bytecode and `JavaField` does not
  expose a constant's value. The split between `SecondRedactionMask` (Checkstyle) and the
  companion audit-SPI rule (ArchUnit) is deliberate for exactly this reason

### Requirement: Where no mechanical check is possible, the reason is recorded in a comment
A rule that bytecode analysis and source-text analysis both cannot express SHALL be recorded as a
comment at the point of the rule, saying why. A rule with neither a check nor such a note is a
rule that will be broken silently.

#### Scenario: A convention cannot be mechanised
- **WHEN** a change introduces a convention no tool in the triad can check — for example that a
  cache's declared `CachePurpose` matches what the TTL actually means
- **THEN** the reason is written at the point of the rule, which is already this repository's
  convention for checks bytecode analysis cannot express

### Requirement: Checkstyle runs on every module at validate
Checkstyle SHALL be bound to the `validate` phase on every module, before the compiler, reading
its configuration from `checkstyle-rules`' source tree for in-reactor builds so a clean clone
builds without installing it first.

#### Scenario: A clean clone is built
- **WHEN** `mvn clean install` is run on a fresh clone with nothing installed locally
- **THEN** Checkstyle resolves its configuration from the in-reactor module and the build
  proceeds, rather than failing on a missing artifact

### Requirement: Formatting mirrors IntelliJ IDEA defaults exactly
The formatting rules SHALL mirror IntelliJ IDEA's out-of-the-box defaults one for one. There is
no IDE code style to import, and Reformat Code / Optimize Imports always produce a build-clean
file.

#### Scenario: A change proposes a custom formatting scheme
- **WHEN** a change would introduce a formatting rule that diverges from IntelliJ's defaults
- **THEN** it is refused: every contributor's IDE would then produce files the build rejects, and
  the cost lands on every commit rather than on the one that introduced it

### Requirement: A suppression names its rule and gives a reason
Every Checkstyle suppression SHALL name the rule and state why. For any rule the configuration
gives an `id`, the id form SHALL be used.

#### Scenario: A rule that shares the RegexpSinglelineJava class name is suppressed
- **WHEN** a suppression is needed for `NonAsciiSourceText`, `ConsoleOutput`,
  `SecondRedactionMask` or any other rule the configuration gives an `id`
- **THEN** the `// SUPPRESS CHECKSTYLE ID <id> - reason` form is used, because several checks
  share the `RegexpSinglelineJava` class name and naming the class switches all of them off at
  once
