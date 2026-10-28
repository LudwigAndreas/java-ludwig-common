## ADDED Requirements

### Requirement: A retried message leaves retention
When an administrator retries a `DEAD_LETTER` message, its `finished_at` SHALL be cleared, so that the purge does
not delete it while it is being retried. If it reaches a terminal status again, its retention SHALL count from that
new transition.

#### Scenario: A dead letter retried on its twenty-ninth day
- **WHEN** a `DEAD_LETTER` message that finished 29 days ago is retried, fails again and becomes `DEAD_LETTER`
  again, and the purge runs two days later
- **THEN** the message still exists
