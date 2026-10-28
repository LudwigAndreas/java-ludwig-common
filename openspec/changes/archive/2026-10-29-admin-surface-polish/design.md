## Context

The change is confined to the internals of `security-spring-boot-starter`, plus the regenerated API documents. It
modifies `admin-authorization`, so the rule-required parts are given here briefly.

## Decisions

1. **`produces` on the mapping.** One attribute on `@GetMapping`. springdoc then publishes `application/json`.
2. **The description check runs at the same point as the handler check.** `AdminHandlerValidator` is the
   `SmartInitializingSingleton` that already fails startup on a handler naming an uncatalogued operation. It gains a
   `ProblemMessages` constructor argument and a second pass over the catalog that resolves each `descriptionKey` through `ProblemMessages.get(key, null, null, defaultLocale)` and collects the
   operations whose key resolves to null. It then fails once, naming all of them, rather than at the first. The
   default locale is the one `i18n-bundles` already defines. Parity across locales is that capability's
   `MessageBundleParity`-style tests, not this check.

**Dependency-direction check.** Does `security-spring-boot-starter` gain an in-repo dependency? No. It already uses
`web-core`'s `ProblemMessages` in `AdminOperationsController`. No edge is added.

**POMs.** No POM changes: not the root `pom.xml`, `ludwig-bom` or `ludwig-service-parent`.

**Mechanical check.** The new convention ("an operation has a description") is enforced at startup by the
starter itself, and an auto-configuration test proves it. No ArchUnit or Checkstyle rule applies, because a bundle
key's presence is a runtime resource fact.

## Risks / Trade-offs

- [A service with an application-level bundle override that removes a key fails to start.] → Intended. The message
  names the key to restore.
