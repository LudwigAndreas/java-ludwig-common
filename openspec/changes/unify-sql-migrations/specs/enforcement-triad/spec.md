## MODIFIED Requirements

### Requirement: Each class of check has exactly one owner
A check SHALL belong to exactly one of three tools:

| Tool | Owns |
|---|---|
| `architecture-rules` (ArchUnit) | structure and dependencies |
| `checkstyle-rules` | source text |
| SonarQube | bugs and security |

The triad governs facts about **Java**. A fact that is neither in bytecode nor in Java source text —
a file's position in the tree, the contents of a resource, the shape of a POM — SHALL be owned by a
**gate script** under `scripts/`, listed in `scripts/gate.sh`'s `COMMANDS` so that it runs wherever
the gate runs. A gate script is a fourth owner, not a fourth opinion: the "exactly one owner" rule
applies across all four, and a check that a triad tool can express SHALL NOT be written as a script.

This records what the repository already does rather than introducing it. `scripts/manifest.sh
layout` owns module placement because a directory's position is a filesystem fact;
`scripts/check_image_pins.sh` owns image-digest pinning because the references live in READMEs and
YAML as well as in Java; `scripts/check_migrations.sh` owns the Liquibase migration layout because a
changelog is a resource.

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

#### Scenario: The rule is about a resource file or a path
- **WHEN** a change needs to check something no triad tool can see — a module in the wrong
  directory, a container image referenced by bare tag, a Liquibase changeset authored in XML
- **THEN** the check is a script under `scripts/`, added to `scripts/gate.sh`'s `COMMANDS`, and it
  is not attempted in ArchUnit or Checkstyle

#### Scenario: A script is proposed for something Checkstyle could check
- **WHEN** a change proposes a gate script to forbid a pattern in Java source text
- **THEN** it belongs in `checkstyle-rules` instead, because the owner is chosen by what kind of
  fact the rule is about, and only a fact outside Java reaches the script tier
