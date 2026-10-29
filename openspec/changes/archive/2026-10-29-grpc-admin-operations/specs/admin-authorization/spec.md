## ADDED Requirements

### Requirement: One evaluation serves every transport
Authorization of an administrative operation, the reason requirement, the audit of denials and the binding of the
administrative invocation SHALL be performed by the same evaluation for HTTP and gRPC, so that a grant, a refusal and
an audit record mean the same on both.

#### Scenario: The same grant on both transports
- **WHEN** a role is granted `outbox.read` and a holder calls an HTTP endpoint and a gRPC method that both require it
- **THEN** both succeed, and a caller without it is refused on both, each refusal producing one audit event of the
  same action
