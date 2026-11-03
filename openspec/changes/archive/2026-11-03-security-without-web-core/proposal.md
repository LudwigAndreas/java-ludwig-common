## Why

`security-spring-boot-starter` declares `web-core-spring-boot-starter` optional, but does not work without it:

- `LudwigSecurityAutoConfiguration` declares two top-level `@Bean` methods whose types are web-core's
  (`ProblemMessageBundle`, and `SecurityProblemMapper implements ExceptionProblemMapper`). Spring cannot introspect
  the class when those types are absent, so **the application fails to start**. A method-level `@ConditionalOnClass`
  cannot prevent this, because introspection happens before any condition is evaluated.
- The two refusals the operation evaluator throws on every surface - `ReasonRequiredException` and
  `PatCredentialNotPermittedException` - extend web-core's `LocalizedException`. Without web-core, a privileged call
  without a reason fails with a linkage error rather than the refusal.

Every service today carries web-core, so nobody has hit this. Any service that reaches the security starter through
a module where web-core is optional - a Kafka worker on job-core, outbox or messaging, a batch job - would. It was
found by `identity-sources`, whose directory starter carries web-core as a test dependency only to work around it.

## What Changes

- **The security starter starts without web-core.** The web-core-typed beans of `LudwigSecurityAutoConfiguration`
  move into a nested configuration that is introspected only where web-core is present.
- **The operation refusals no longer depend on web-core.** `ReasonRequiredException` and
  `PatCredentialNotPermittedException` become the security starter's own exceptions, carrying their code, status,
  arguments and properties. `SecurityProblemMapper`, which exists only where web-core does, renders them - so HTTP
  and gRPC answer exactly as today: `400` / `INVALID_ARGUMENT` with the reason-required problem type, `403` /
  `PERMISSION_DENIED` with the credential-not-permitted problem type.
- **A test proves it on a classpath without web-core**, not by hiding classes from conditions: a separate Surefire
  execution of the security starter runs with web-core removed from the test classpath.
- `identity-source-directory-spring-boot-starter` drops the test-scoped web-core it carried as a workaround.

No configuration key, endpoint, problem type or wire behaviour changes.

## Capabilities

### New Capabilities

### Modified Capabilities
- `starter-conditional-wiring`: adds the requirement that the security starter starts, and refuses privileged calls
  with its own refusals, on a classpath without web-core.

## Non-goals

- Making web-core mandatory for the security starter. It brings the servlet problem pipeline, which a worker does not
  need; the dependency stays optional and becomes honest.
- Auditing every other starter that treats web-core as optional. In those, web-core types appear only in
  administrative packages, which exist only where the servlet stack and web-core do; no non-web path depends on them.
- Rendering refusals without web-core. A service without web-core has no problem pipeline to render into; the
  refusal propagates as the security exception it is.

## Impact

- `security-spring-boot-starter` - library (parent `common`). In-repo dependents: audit, crud-service-example,
  export, file-action, file-ingest, grpc-client, grpc-server, hot-reload, identity-projection, job-core,
  messaging-db, notification-service, outbox, pat, reconciliation, rest-client, test-support-security,
  user-settings. Changes: `LudwigSecurityAutoConfiguration`, the two exceptions, `SecurityProblemMapper`, a
  Surefire execution in its POM, a test.
- `identity-source-directory-spring-boot-starter` - library (parent `common`); no in-repo dependents. Its POM drops
  the test-scoped web-core.
- Shared contracts in `openspec/specs/`: `starter-conditional-wiring` is modified. `operation-authorization` and
  `personal-access-token` name the two problem types; their requirements are unchanged and are what the existing
  MVC and gRPC tests keep asserting.
- Docs: the security starter's README (EN and RU), `CHANGELOG` (EN and RU), and the follow-up entry removed from
  `openspec/intents/admin-surface-follow-ups.md`.
