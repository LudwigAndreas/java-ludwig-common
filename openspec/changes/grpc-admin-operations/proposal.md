## Why

The administrative model (operations, grants, reasons, audit) exists only for HTTP under `/admin/**`. A service that
exposes administrative functions to automation over gRPC has no way to authorize them by operation. Worse,
`@AdminAccess` placed on a `@LudwigGrpcService` method today compiles, starts, and is silently ignored: gRPC
dispatches to the bound object, not to a proxy or interceptor that reads the annotation, and `RuleGroup.GRPC` does
not list `@AdminAccess` among the annotations it refuses. This was out of scope in `admin-authorization`
(`openspec/intents/admin-surface-follow-ups.md` §2).

## What Changes

- **`@AdminAccess` on gRPC methods.** `grpc-server-spring-boot-starter` honours `@AdminAccess` on methods of
  `@LudwigGrpcService` classes. It reads the annotation reflectively at bind time, as it already does for
  `@Idempotent`, and enforces it in a server interceptor running after authentication:
  - an operation the caller does not hold is `PERMISSION_DENIED`, audited exactly as an HTTP administrative
    denial;
  - a privileged operation without `admin-reason` metadata is `INVALID_ARGUMENT`;
  - the handler runs with `AdminInvocation` bound, so `AdminAudit` records the operation, reason and actor as it
    does for HTTP;
  - tenant confinement and object scope apply through the same application services.
- **The same startup checks.** A gRPC method naming an operation the catalog does not contain fails startup,
  matching `AdminHandlerValidator` for HTTP.
- **A public entry point in the security starter.** `security-spring-boot-starter` gains
  `AdminAccessEvaluator.authorize(Authentication, LudwigPrincipal, AdminOperation, String reason)` and a public
  `AdminInvocation.Scope` for binding outside MVC. The MVC path is refactored onto it, so both transports share one
  implementation.
- **No platform admin protos.** Starters keep their administrative APIs on HTTP. A service that wants gRPC
  administration declares its own service in its contract module and annotates the adapter methods.

## Capabilities

### New Capabilities
None.

### Modified Capabilities
- `grpc-server`: honours `@AdminAccess` and the reason metadata.
- `admin-authorization`: administrative operations can be enforced on gRPC methods, with the same checks and audit.

## Non-goals

- gRPC versions of the starters' administrative HTTP APIs.
- Four-eyes approval over gRPC (`admin-four-eyes-approval` is HTTP-only).
- Server reflection changes.

## Impact

- `grpc-server-spring-boot-starter` (library; parent `common`). In-repo dependents: `crud-service-example` (service)
  and `grpc-client-spring-boot-starter` (test).
- `security-spring-boot-starter` (library; parent `common`), which has 16 in-repo dependents.
- `architecture-rules`: `RuleGroup.GRPC`'s javadoc names `@AdminAccess` as honoured, like `@Idempotent`. No rule is
  removed.
- Shared contracts: `grpc-server` and `admin-authorization` are modified.
- No POM changes. `grpc-server` already depends on `security-spring-boot-starter`.
- READMEs (EN and RU) and `CHANGELOG` (EN and RU).
