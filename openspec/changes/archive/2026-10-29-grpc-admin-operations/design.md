## Context

This crosses `security-spring-boot-starter` (the evaluator) and `grpc-server-spring-boot-starter` (enforcement), and
changes two shared contracts.

- `AdminAccessEvaluator.authorize` currently takes a `HandlerMethod`.
- `AdminInvocation.bind` is package-private, so only the MVC guard can bind.
- `grpc-server` already reads `@Idempotent` reflectively at bind time (`IdempotentReplayInterceptor`).
- `AuthenticationInterceptor` already establishes `LudwigAuthentication`.

## Decisions

1. **Split the evaluator.** `authorize(Authentication, LudwigPrincipal, AdminOperation, String reason)` returns an
   `AdminInvocation.Scope` (`AutoCloseable`) that binds the invocation for the current thread. It throws
   `AccessDeniedException` after publishing `AuthorizationDeniedEvent`, or `AdminReasonRequiredException`.

   The `HandlerMethod` overload resolves the operation from the annotation and delegates. The MVC guard closes the
   scope in `afterCompletion`. Keeping a single implementation is the point (see the new `admin-authorization`
   requirement).
2. **`AdminAccessInterceptor` in grpc-server.** It is ordered after `AuthenticationInterceptor` and
   `ContextBridgeInterceptor`, so the principal is on the thread.
   - At bind time it maps `fullMethodName` to the `@AdminAccess` operation for every service method that carries
     one.
   - On each call to a mapped method it reads the `admin-reason` metadata (ASCII, at most 512 characters, the same
     cap as the HTTP header) and calls `authorize`.
   - It opens the scope inside the call's context bridge, so the handler thread holds it and nothing leaks after
     the callback returns. This is the same lifecycle the existing context bridge already guarantees.
   - Exceptions go through the existing `ExceptionTranslatingInterceptor`, which maps `AccessDeniedException` to
     `PERMISSION_DENIED` and the reason exception to `INVALID_ARGUMENT`. That mapping is added to `GrpcProblemRenderer`
     if it is not already present.
3. **The startup check** sits beside the binding map. An unknown operation fails startup with the method name.
4. **`RuleGroup.GRPC` keeps refusing the proxy annotations.** Its javadoc and failure message now name
   `@AdminAccess` with `@Idempotent` as honoured. No mechanical change is needed, because the gap was a silently
   ignored annotation that is now honoured, and a test proves it is honoured.

**Dependency-direction check.** Does `grpc-server-spring-boot-starter` gain an in-repo dependency? No. It already
depends on `security-spring-boot-starter`. No edge is added.

**POMs.** No POM changes: not the root `pom.xml`, `ludwig-bom` or `ludwig-service-parent`.

## Risks / Trade-offs

- [Streaming methods hold the scope for the stream's lifetime.] → They are allowed, and the scope is opened per
  call, not per message. The reason is read once, from the call's headers.
- [The evaluator refactor could change MVC behaviour.] → `AdminSurfaceMvcTest` and every area IT run unchanged in the
  gate.
