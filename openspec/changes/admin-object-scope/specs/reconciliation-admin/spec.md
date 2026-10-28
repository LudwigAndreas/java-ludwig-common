## ADDED Requirements

### Requirement: Reconciliation tasks are scoped objects
Every reconciliation administrative endpoint naming `{task}` SHALL treat the task name as its administrative object,
and `GET /admin/v1/reconciliation/tasks` SHALL list only the tasks the caller's `read` scope admits.

#### Scenario: Listing under a scope
- **WHEN** read scoping is on and bob is granted only `supplier-sync`
- **THEN** the task listing contains `supplier-sync` and no other task
