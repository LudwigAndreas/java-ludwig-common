## 1. Evaluator (`security-spring-boot-starter`)

- [x] 1.1 `authorize(Authentication, LudwigPrincipal, AdminOperation, reason)` returning `AdminInvocation.Scope`; MVC
      guard refactored onto it; verify `mvn -pl :security-spring-boot-starter test` (every admin test unchanged and
      green)

## 2. Enforcement (`grpc-server-spring-boot-starter`)

- [x] 2.1 `AdminAccessInterceptor`, bind-time map and startup check, `admin-reason` metadata, scope inside the context
      bridge, status mapping; verify `mvn -pl :grpc-server-spring-boot-starter verify -Dit.test=GrpcAdminAccessIT`
      covering the four `grpc-server` scenarios with a test-only service
- [x] 2.2 Cross-transport scenario: a test application with one HTTP endpoint and one gRPC method on the same operation;
      verify as 2.1

## 3. Rules and documentation

- [x] 3.1 `GrpcRules` javadoc and message name `@AdminAccess` as honoured; verify `mvn -pl :architecture-rules -am
      verify`
- [x] 3.2 grpc-server README (EN and RU): "administrative methods" section; remove the gRPC item from the follow-ups list;
      verify heading-count parity and `openspec validate grpc-admin-operations --strict`

## 4. Verification gate

- [x] 4.1 `scripts/gate.sh --change grpc-admin-operations security-spring-boot-starter grpc-server-spring-boot-starter
      architecture-rules`
