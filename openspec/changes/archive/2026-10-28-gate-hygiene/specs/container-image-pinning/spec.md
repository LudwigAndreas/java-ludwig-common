## ADDED Requirements

### Requirement: The repository checker examines code, not prose, in Markdown
`scripts/check_image_pins.sh` SHALL examine, in a Markdown file, only lines inside fenced code blocks and text
inside inline code spans. Every other tracked file it covers SHALL be examined line by line as before. It SHALL
provide `--self-test`, which runs it over fixtures that make each trigger both report and not report, and which
fails if any fixture is classified wrongly.

#### Scenario: Prose that reads like a YAML key
- **WHEN** a Markdown paragraph, outside any code, contains the words no new image: the mail server
- **THEN** the checker reports no reference for that line

#### Scenario: A tag-only image in a README code example
- **WHEN** a fenced code block in a README runs the image postgres by its tag 16 alone, with no digest
- **THEN** the checker fails, naming the file, the line and that reference

#### Scenario: The self-test catches a regression
- **WHEN** a trigger's pattern is changed so that a `bad` fixture is no longer reported
- **THEN** `scripts/check_image_pins.sh --self-test` exits non-zero
