## ADDED Requirements

### Requirement: The trail can be searched by acting party
`GET /admin/v1/audit/events` SHALL accept `via`, matching events whose actor's acting party equals it exactly,
combinable with every existing filter and subject to the same tenant confinement. Every returned event SHALL
carry its `via`.

#### Scenario: Everything done through the administrative UI yesterday
- **WHEN** an auditor searches `via=admin-console` with yesterday's occurred-at range
- **THEN** only events whose actor acted through `admin-console` in that range are returned, each with `via` =
  `admin-console`
