## ADDED Requirements

### Requirement: An administrator may force-release a lease, and the holder stops at its next renewal

A forced release SHALL have exactly the effect of a release: the owner is nulled and `expires_at` moved to `now()`,
in a statement outside any caller transaction. It SHALL NOT delete the row. Because renewal is conditional on
`owner` and `run_id`, a holder that is still alive SHALL fail its next renewal and stop, as it would after losing the
lease to expiry.

#### Scenario: The holder is dead
- **WHEN** a lease whose holder process no longer exists is force-released
- **THEN** the next acquisition attempt by any instance succeeds without waiting for `expires_at`

#### Scenario: The holder is alive
- **WHEN** a lease is force-released while its holder is mid-run
- **THEN** the holder's next `renew` returns false and the holder stops at once, and an instance that acquires the
  lease meanwhile is the only one that continues

#### Scenario: A lease that is not held
- **WHEN** a lease with no owner is force-released
- **THEN** the row is unchanged and the release reports that it held nothing
