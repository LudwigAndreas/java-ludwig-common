## ADDED Requirements

### Requirement: Administrative operations are enforced on gRPC methods
A method of a `@LudwigGrpcService` annotated `@AdminAccess(<operation>)` SHALL be invoked only when the authenticated
caller holds that operation. Otherwise the call SHALL fail with `PERMISSION_DENIED` before the handler runs, and the
denial SHALL be audited as an administrative denial. A privileged operation SHALL require non-blank `admin-reason`
call metadata, else `INVALID_ARGUMENT`. The handler SHALL run with the administrative invocation bound, so its audit
events record the operation and reason. A method naming an operation absent from the catalog SHALL fail startup.

#### Scenario: Automation calls an administrative method it is granted
- **WHEN** a service principal holding `catalog.operate` calls a method annotated `@AdminAccess("catalog.operate")`
- **THEN** the handler runs, and its audit event names the operation

#### Scenario: A caller without the operation
- **WHEN** a caller holding only `catalog.read` calls the same method
- **THEN** the call fails with `PERMISSION_DENIED`, the handler is not invoked, and the denial is audited

#### Scenario: A privileged call without a reason
- **WHEN** a caller holding `catalog.privileged` calls a privileged method with no `admin-reason` metadata
- **THEN** the call fails with `INVALID_ARGUMENT` and the handler is not invoked

#### Scenario: A typo in the operation
- **WHEN** a gRPC method is annotated `@AdminAccess("catalgo.read")`
- **THEN** the application fails to start naming the method and the operation
