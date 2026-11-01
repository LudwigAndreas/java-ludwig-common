## MODIFIED Requirements

### Requirement: Administrative operations are enforced on gRPC methods
A method of a `@LudwigGrpcService` annotated `@RequiresOperation(<operation>)` SHALL be invoked only when the
authenticated caller holds that operation and, for an `ADMIN` operation, is admitted to the administrative surface. Otherwise the call SHALL fail with
`PERMISSION_DENIED` before the handler runs, and the denial SHALL be audited as an authorization denial. An
operation of sensitivity `PRIVILEGED` SHALL require non-blank `operation-reason` call metadata of at most 500 characters, else
`INVALID_ARGUMENT`. The handler SHALL run with the invocation bound, so its audit events record the
operation and reason. Startup SHALL fail, naming the method, when an annotated method names an operation absent
from the catalog, names an operation that requires four-eyes approval, names an `ADMIN` operation while no
administrative surface is configured, or exists while no operation catalog is configured. Methods without the
annotation are unaffected.

#### Scenario: Automation calls an administrative method it is granted
- **WHEN** a service principal holding `catalog.product.update` calls a method annotated
  `@RequiresOperation("catalog.product.update")`
- **THEN** the handler runs, with the invocation naming `catalog.product.update` bound

#### Scenario: A caller without the operation
- **WHEN** a caller holding only `catalog.product.read` calls the same method
- **THEN** the call fails with `PERMISSION_DENIED`, the handler is not invoked, and the denial is audited

#### Scenario: A privileged call without a reason
- **WHEN** a caller holding `catalog.product.purge`, a `PRIVILEGED` operation, calls its method with no `operation-reason` metadata
- **THEN** the call fails with `INVALID_ARGUMENT` and the handler is not invoked; with `operation-reason: INC-7` it runs
  with that reason bound

#### Scenario: A typo in the operation
- **WHEN** a gRPC method is annotated `@RequiresOperation("catalgo.product.read")`
- **THEN** the application fails to start naming the method and the operation

#### Scenario: No operation catalog
- **WHEN** a gRPC method carries `@RequiresOperation` and the service has no operation catalog configured
- **THEN** the application fails to start naming the method, rather than serving it to any authenticated caller

#### Scenario: An operation that requires approval
- **WHEN** a gRPC method's operation is listed in `ludwig.admin.approval.required`
- **THEN** the application fails to start naming the method, because four-eyes approval exists only over HTTP

#### Scenario: An administrative operation without the administrative surface
- **WHEN** a gRPC method's operation is an `ADMIN` operation and `ludwig.admin` is not configured
- **THEN** the application fails to start naming the method and the operation
