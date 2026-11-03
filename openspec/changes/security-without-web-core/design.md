## Context

See proposal.md - Why. The facts that shape the fix, read from the code:

- `LudwigSecurityAutoConfiguration` (top level) declares `ludwigSecurityProblemMessageBundle()` returning web-core's
  `ProblemMessageBundle`, and `ludwigSecurityProblemMapper()` returning `SecurityProblemMapper`, which implements
  web-core's `ExceptionProblemMapper`. Both carry a method-level `@ConditionalOnClass`, which cannot help: Spring
  reflects over the class's declared methods while parsing it, and their signatures name the absent types.
- `OperationAutoConfiguration` already does this correctly: its web-core beans live in nested `@Configuration`
  classes guarded by `@ConditionalOnClass(name = ...)`. `AdminAutoConfiguration` is guarded at class level and only
  exists on a servlet stack with web-core.
- `ReasonRequiredException` (`ru.ludwigandreas.security.operation`) and `PatCredentialNotPermittedException`
  (`ru.ludwigandreas.security.web`) extend web-core's `LocalizedException`. `OperationEvaluator` throws both on every
  surface; `AdminCallerPolicy` and pat's `PatCredentialGuard` throw the second. Both HTTP and gRPC render exceptions
  through web-core's `ProblemMapperRegistry` (gRPC via `GrpcProblemRenderer`), so a mapper is all either needs.
- Found during apply: `AdminObjectNotFoundException` (`ru.ludwigandreas.security.admin`) also extends
  `LocalizedException`, and `OperationEvaluator` throws it for an object outside the caller's scope, so it is linked
  wherever the evaluator is. It gets the same treatment as the other two and renders `NOT_FOUND`.
- Nothing catches these two as `LocalizedException`; web-core's generic `LocalizedExceptionProblemMapper` is the
  only thing that recognised them.

This change is confined to the security starter, plus one test-dependency removal in the directory starter.

## Goals / Non-Goals

**Goals:** the security starter starts and refuses correctly on a classpath without web-core; with web-core, every
refusal renders byte-for-byte as before.

**Non-Goals:** see proposal.md - Non-goals.

## Decisions

### 1. Web-core beans in a nested, name-guarded configuration

`ludwigSecurityProblemMessageBundle` and `ludwigSecurityProblemMapper` move into
`LudwigSecurityAutoConfiguration.WebCoreProblems`, a static nested `@Configuration(proxyBeanMethods = false)` with
`@ConditionalOnClass(name = "ru.ludwigandreas.webcore.problem.ExceptionProblemMapper")`. Spring parses a nested
configuration only after its condition passes, so its method signatures are never reflected over without web-core.
Bean names and `@ConditionalOnMissingBean` guards stay as they are, so an application overriding either is
unaffected.

*Alternative:* make web-core a mandatory dependency (rejected: it brings the servlet problem pipeline into workers
that have no HTTP surface, and the dependency was declared optional on purpose).

### 2. Refusals are the security starter's own; the mapper renders them

A new abstract `ru.ludwigandreas.security.exception.SecurityRefusalException extends RuntimeException` carries a
code, message arguments and problem properties - the data `LocalizedException` carried - with no web-core type.
`ReasonRequiredException` and `PatCredentialNotPermittedException` keep their names, packages, codes, arguments and
properties, and extend it instead. `SecurityProblemMapper` (already the security starter's `ExceptionProblemMapper`,
and now only loaded with web-core) gains them: `ReasonRequiredException` to `ProblemStatus.INVALID`, and
`PatCredentialNotPermittedException` to `ProblemStatus.FORBIDDEN`, each with the exception's code, arguments and
properties. The status is the mapper's decision, not the exception's, because `ProblemStatus` is web-core's.

*Alternative:* keep extending `LocalizedException` and move web-core to mandatory (rejected, as above); duplicate the
exceptions per transport (rejected: two classes for one refusal, and the gRPC path already reuses the HTTP mappers).

### 3. Proven on a classpath without web-core

`FilteredClassLoader` hides classes from conditions but not from linkage - the auto-configuration classes are still
defined by the application classloader, which sees web-core - so a test using it would pass against the broken code.
The security starter's POM gains a second Surefire execution, `without-web-core`, with
`<classpathDependencyExcludes>ru.ludwigandreas:web-core-spring-boot-starter</classpathDependencyExcludes>`, running
only `**/*WithoutWebCoreTest.java`; the default execution excludes that pattern. The test starts every security
auto-configuration, and calls a `PRIVILEGED` operation through `OperationEvaluator` without a reason and with a
personal access token, asserting the two refusals.

The existing `OperationEnforcementMvcTest`, `AdminSurfaceGuardTest` and `GrpcAdminAccessIT` are what keep the
rendered problems identical; they are not changed.

### Dependency-direction check

The change adds no in-repo dependency edge and removes one test-scoped edge
(`identity-source-directory-spring-boot-starter` -> `web-core-spring-boot-starter`, test). Does
`web-core-spring-boot-starter` depend on `security-spring-boot-starter`, directly or transitively, such that the
existing optional edge security -> web-core could form a cycle? `project-index.json` inRepoDependents for
`security-spring-boot-starter` does not contain `web-core-spring-boot-starter`, so no cycle, and none is introduced.

### POMs

- `pom.xml` (root), `ludwig-bom`, `ludwig-service-parent`: no change.
- `security-spring-boot-starter/pom.xml`: the second Surefire execution (build configuration local to the module).
- `identity-source-directory-spring-boot-starter/pom.xml`: the test-scoped web-core dependency is removed.

### Conventions and their checks

| Convention | Check | Owner |
|---|---|---|
| The security starter starts and refuses without web-core | the `without-web-core` Surefire execution of `*WithoutWebCoreTest` | the module's build |

No ArchUnit or Checkstyle rule can express "links without an optional dependency"; a classpath that lacks it is
the only honest check. Nothing platform-centralised is reimplemented: refusals still render through the one problem
pipeline.

## Risks / Trade-offs

- [A future web-core-typed bean added at the top level of a security auto-configuration] → the `without-web-core`
  execution fails the build, which is the point of running it on every `verify`.
- [An application that relied on these refusals being `LocalizedException`] → none in the repository; the problem
  types, codes and properties on the wire are unchanged, which is what clients see.
- [The `without-web-core` classpath still carries web-core's own transitive dependencies (Spring MVC)] → the claim
  under test is "without web-core", which is the optional edge; a worker without Spring MVC is a stricter case the
  existing `@ConditionalOnWebApplication` guards already cover.

## Migration Plan

None for consumers: no key, endpoint, problem type or wire behaviour changes. Rollback is reverting the change.
