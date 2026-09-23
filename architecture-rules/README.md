# architecture-rules

***English** · [Русский](README.ru.md)*

Executable architecture conventions for Spring Boot services: a test-scoped jar of pre-built
[ArchUnit](https://www.archunit.org) rule sets - layering, package cycles, REST boundary, JPA
persistence, Kafka messaging and contracts, S3 storage, Spring wiring, exception architecture, API
model immutability, entity inheritance, REST path versioning, configuration validation, module
boundaries, test separation and configuration access - that a service switches on with a single
annotation. Every rule is independently toggleable, every convention is configurable, and both can be
overridden for one module or a list of modules. Every run writes two reports: a colourised console
summary for the person whose build just went red, and a JSON file for tooling, coding agents and the
org-wide architecture dashboard.

## Why

Conventions that live in a document, in a reviewer's head or in one reference service decay silently.
They are re-explained in every code review, applied unevenly across teams, and the drift is only
discovered when something breaks: a JPA entity that has quietly become a published REST contract, a
controller that queries the database and skips the transaction boundary, two "modules" that grew into
each other and can no longer be released apart.

Shipping the conventions as a jar changes who checks them. A service adds one test dependency and one
annotation, and from then on its own build proves compliance - on every commit, before review,
naming the exact rule that broke.

## Quick start

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>architecture-rules</artifactId>
    <version>1.0.0</version>
    <scope>test</scope>
</dependency>
```

```java
@AnalyzeArchitecture(packagesOf = OrdersApplication.class)
class ArchitectureTest extends ArchitectureRulesTest {
}
```

That is the whole integration. Each enabled rule becomes its own JUnit test named by its id, so the
build report reads:

```
Architecture rules
  +-- layering.layered-architecture                              OK
  +-- web.controllers-do-not-expose-entities                     FAILED
  +-- persistence.entities-reside-in-entity-packages             OK
  +-- cycles.module-internals-are-free-of-cycles[com.acme.orders] OK
```

and the run leaves a colourised summary on the console plus a machine-readable
`target/architecture-report.json` behind - see [Reports](#reports).

A service that has no Kafka switches those rules off; nothing else changes:

```java
@AnalyzeArchitecture(
        packagesOf = OrdersApplication.class,
        enable = "domain-isolation",
        disable = {"kafka", "storage"})
class ArchitectureTest extends ArchitectureRulesTest {
}
```

## What is checked

Nineteen groups, 46 rules. Every id below is a selector: pass the group id to toggle the group, the
full id to toggle one rule.

Rules that check against a name only the consuming organisation can supply - the base exception, the
base entity, the publisher interface - are built either way. When the name is missing, the rule fails
*only the classes it would have checked*, with the property to set and the rule to disable spelled out
in the message. A service with no Kafka producer, no entity or no custom exception therefore stays
green without configuring anything, and a service that has them cannot end up with a rule that quietly
checks nothing.

### `layering` - Controller -> Service -> Repository -> Domain

| Rule id | What it enforces |
|---|---|
| `layering.layered-architecture` | Controllers are not accessed by anything; the service layer is reachable only from controllers, messaging, storage adapters, mappers and configuration; repositories only from the service layer |
| `layering.controllers-do-not-access-persistence` | The same boundary stated directly as a package dependency, so it cannot be weakened by a layer definition that happens to miss a diamond |

Mappers are a layer of their own: a MapStruct mapper in `..web.mapper..` translates between two
layers' models by definition, so it may look both ways and is never treated as an entry point.

### `cycles` - no package cycles

| Rule id | What it enforces |
|---|---|
| `cycles.modules-are-free-of-cycles` | No cycles between the modules/bounded contexts of the service |
| `cycles.module-internals-are-free-of-cycles[<module>]` | No cycles between the packages inside one module - one rule instance per module |

Modules are discovered as the direct sub-packages of the base packages, so a new feature is covered
the day it is created. Declare them explicitly with `modules = {...}` if the service slices
differently.

### `domain-isolation` - a framework-free domain model (opt-in)

| Rule id | What it enforces |
|---|---|
| `domain-isolation.domain-is-free-of-spring` | No Spring types in the domain packages |
| `domain-isolation.domain-is-free-of-persistence` | No JPA/Hibernate types or annotations in the domain packages |
| `domain-isolation.domain-is-free-of-json` | No JSON binding library in the domain packages |

Off by default: it only means something in a service that keeps a domain model separate from its
persistence entities. Enable with `enable = "domain-isolation"` and point `PackageRole.DOMAIN` at the
model packages.

### `web` - the REST boundary

| Rule id | What it enforces |
|---|---|
| `web.controllers-do-not-expose-entities` | No JPA entity in a controller method signature, type arguments included (`ResponseEntity<PageResponse<ProductEntity>>` is caught) |
| `web.controllers-do-not-use-persistence-types` | No repository, `EntityManager` or `JdbcTemplate` inside a controller |
| `web.controllers-do-not-call-controllers` | Shared behaviour belongs in a service, not in a second controller |
| `web.dtos-are-not-jpa-entities` | A DTO carries no persistence annotations and no persistence API |
| `web.dtos-are-not-event-payloads` | Each boundary owns its own model |

### `persistence` - Postgres/JPA

| Rule id | What it enforces |
|---|---|
| `persistence.entities-reside-in-entity-packages` | `@Entity`/`@MappedSuperclass`/`@Embeddable` only in the entity packages |
| `persistence.repositories-reside-in-repository-packages` | Spring Data repositories and `@Repository` classes only in the repository packages |
| `persistence.repositories-are-interfaces` | A Spring Data repository is an interface, never a class |
| `persistence.repositories-are-used-only-by-services` | Only the service layer (and Spring's wiring) reaches a repository |
| `persistence.persistence-context-is-confined` | `EntityManager`, Hibernate `Session` and JDBC stay in the persistence layer |

### `kafka` - messaging

| Rule id | What it enforces |
|---|---|
| `kafka.clients-are-confined-to-messaging` | Only the messaging (and configuration) packages touch the Kafka API |
| `kafka.consumers-reside-in-messaging-packages` | `@KafkaListener` classes live with the other adapters |
| `kafka.consumers-do-not-use-repositories` | A consumer is an entry point: it calls the service layer, like a controller |
| `kafka.payloads-are-free-of-jpa` | A payload is a wire contract, not a table |
| `kafka.payloads-are-not-rest-dtos` | A topic and an HTTP endpoint are versioned separately |
| `kafka.messaging-does-not-depend-on-controllers` | Two entry points do not call each other |

### `storage` - S3 and other object storage

| Rule id | What it enforces |
|---|---|
| `storage.sdk-is-confined-to-storage-adapters` | Only the storage adapters (and the configuration that builds the client) see `software.amazon.awssdk..`/`com.amazonaws..` |
| `storage.adapters-do-not-expose-sdk-types` | *Opt-in.* The adapter's public methods speak the service's own types, not the SDK's |

### `spring` - wiring and transactions

| Rule id | What it enforces |
|---|---|
| `spring.configuration-classes-reside-in-config-packages` | Bean definitions in the configuration packages (the `@SpringBootApplication` class is exempt - it belongs in the root package) |
| `spring.beans-use-constructor-injection` | No field-level `@Autowired`/`@Inject`/`@Resource` |
| `spring.transactional-classes-are-in-the-service-layer` | `@Transactional` classes in the service layer |
| `spring.transactional-methods-are-in-the-service-layer` | `@Transactional` methods in the service layer |
| `spring.singleton-beans-have-no-mutable-state` | No non-final instance fields on `@Service`/`@Component`/`@RestController`/`@Configuration`/`@ControllerAdvice`/`@RestControllerAdvice` - Spring keeps one instance of each, so a mutable field is shared across every concurrent request |

Constructor injection is here rather than in a style checker because it is a dependency question: a
field-injected bean cannot be constructed in a test without a container, cannot be final, and hides
how many collaborators it has. If your team considers that Checkstyle's job, disable the one rule.

### `modules` - bounded-context boundaries

| Rule id | What it enforces |
|---|---|
| `modules.internals-are-not-accessed-from-other-modules` | Nothing reaches below another module's `internal` package |
| `modules.cross-module-access-goes-through-the-api-package` | *Opt-in.* The whitelist variant: a module is reachable only through its `api` package |

### `tests` - test/production separation

| Rule id | What it enforces |
|---|---|
| `tests.production-code-does-not-depend-on-test-frameworks` | JUnit, Mockito, AssertJ, Testcontainers and friends stay in the test sources - a test-scoped dependency is absent at runtime, so this fails in production, not in the build |

### `configuration` - environment access

| Rule id | What it enforces |
|---|---|
| `configuration.environment-is-read-only-by-configuration-classes` | `System.getenv`/`System.getProperty` only in `@Configuration`/`@ConfigurationProperties` classes |

On Kubernetes configuration arrives as environment variables and ConfigMaps. A `System.getenv` in a
service method is invisible to a manifest review, has no default, no type and no validation, and
fails on the first request that needs it instead of at pod startup.


### `exceptions` - one hierarchy, one place that translates it

| Rule id | What it enforces |
|---|---|
| `exceptions.custom-exceptions-extend-the-base-exception` | Every throwable the service defines extends the configured base exception (`conventions.types.base-exception`) |
| `exceptions.controllers-do-not-catch-checked-exceptions` | Controllers let exceptions out; `@ControllerAdvice`/`@RestControllerAdvice` translates them |
| `exceptions.kafka-listeners-do-not-catch-checked-exceptions` | A listener that swallows a failure acknowledges the message as processed - let the container retry or dead-letter it |

`java.lang.Throwable` is excluded from the catch rules on purpose: try-with-resources and `finally`
compile into catch-all handlers that bytecode cannot tell apart from a hand-written `catch (Throwable
t)`. Everything a developer actually names - `IOException`, `Exception`, a checked exception of the
service's own - is reported.

### `dto-immutability` - the API model is built once

| Rule id | What it enforces |
|---|---|
| `dto-immutability.api-models-have-no-setters` | No public `setXxx(T)` on anything in the DTO/API packages |

This checks the compiled API surface, not how it was produced - which is exactly why it works where
"detect Lombok usage" cannot: a Lombok-generated setter and a hand-written one are the same bytecode.

### `entity-base` - one audit base class

| Rule id | What it enforces |
|---|---|
| `entity-base.entities-extend-the-base-entity` | Every `@Entity` is assignable to the configured base entity (`conventions.types.base-entity`) |

`@MappedSuperclass` and `@Embeddable` are not asked to extend anything: the first is usually the base
class itself, the second has no identity.

### `kafka-contracts` - producers and consumers implement the service's own contracts

| Rule id | What it enforces |
|---|---|
| `kafka-contracts.producers-implement-the-publisher-interface` | A class using the Kafka producer API implements the configured publisher interface (`conventions.types.event-publisher`) |
| `kafka-contracts.consumers-implement-the-handler-interface` | Built only when `conventions.types.event-consumer` is configured - many services keep plain annotated listeners |

The contract half of `kafka`: that group says where the Kafka API may appear, this one says what the
class using it has to be. A package boundary still permits five publishers with five different retry,
header and serialization behaviours inside `..messaging..`.

### `rest-paths` - one versioned path shape across the estate

| Rule id | What it enforces |
|---|---|
| `rest-paths.controllers-declare-a-versioned-base-path` | Every `@RestController` declares `@RequestMapping` whose path matches `conventions.settings.rest-base-path-pattern` (default `/api/v\d+(/.*)?`) |

Implemented as a condition over the annotation's member values - the path is a string inside the
annotation, which no dependency rule can see.

### `configuration-properties` - bad configuration fails the pod, not the request

| Rule id | What it enforces |
|---|---|
| `configuration-properties.configuration-properties-are-validated` | Every `@ConfigurationProperties` class is also `@Validated` |

### `optional` - `Optional` is a return type

| Rule id | What it enforces |
|---|---|
| `optional.not-used-as-a-field-type` | No field declared as `Optional` |
| `optional.not-used-as-a-parameter-type` | No method or constructor takes an `Optional` |

Declared types only. Finding methods that return `null` where they should return an `Optional` needs
dataflow analysis and belongs to a nullness checker (Sonar, NullAway, ErrorProne) - see
[what this library deliberately does not check](#what-this-library-deliberately-does-not-check).

### `mappers` - mappers are MapStruct interfaces

| Rule id | What it enforces |
|---|---|
| `mappers.mappers-are-mapstruct-interfaces` | A class in a mapper package, or whose name ends with the configured suffix, is an interface annotated `@org.mapstruct.Mapper` |

Generated `*MapperImpl` classes and the nested types the generator emits next to them are excluded -
by assignability and by nesting, not by name, since MapStruct's `@Generated` has source retention and
never reaches the bytecode. The rule locks the convention for classes already identified as mappers;
it does not look for mapping logic written elsewhere.

## Configuring the conventions

Nothing in the rules hardcodes a package name. A service maps its own layout onto the library's
vocabulary once - by annotation, in a properties file, or in code.

| Role | Default packages |
|---|---|
| `controller` | `..controller..`, `..web..`, `..rest..` |
| `service` | `..service..`, `..application..`, `..usecase..` |
| `repository` | `..repository..`, `..persistence..`, `..dao..` |
| `entity` | `..entity..`, `..entities..` |
| `domain` | `..domain..` |
| `dto` | `..dto..`, `..request..`, `..response..` |
| `mapper` | `..mapper..`, `..mappers..` |
| `configuration` | `..config..`, `..configuration..` |
| `messaging` | `..messaging..`, `..kafka..` |
| `event-payload` | `..event..`, `..events..` |
| `storage` | `..storage..`, `..s3..` |
| `transactional-host` | `..service..`, `..application..`, `..usecase..` |
| `module-internal` | `internal` (a package segment, not an identifier) |
| `module-api` | `api` (a package segment, not an identifier) |

Types a service names for itself (`conventions.types.*`), with no default - the rules that use them
say so rather than guessing:

| Type role | Meaning |
|---|---|
| `base-exception` | The root of the service's exception hierarchy, e.g. `com.acme.common.ApplicationException` |
| `base-entity` | The audit base class every `@Entity` inherits, e.g. `ru.ludwigandreas.db.core.entity.AbstractEntity` |
| `event-publisher` | The internal contract every Kafka producer implements, e.g. `com.acme.messaging.EventPublisher` |
| `event-consumer` | Optional: the contract every listener implements. The rule exists only once this is set |

Free-form settings (`conventions.settings.*`):

| Setting | Default | Meaning |
|---|---|---|
| `rest-base-path-pattern` | `/api/v\d+(/.*)?` | Regular expression every `@RestController` base path must match |
| `mapper-name-suffix` | `Mapper` | Simple-name suffix that identifies a mapper, alongside the mapper packages |

Alongside the packages, three more dimensions are configurable the same way: `libraries` (which
packages are Spring, JPA, Jackson, Kafka, the AWS SDK, the test frameworks), `annotations` (what
marks a controller, a persistent type, a configuration class, an injection point, a Kafka listener)
and `types` (what a Spring Data repository, a persistence context, a JDBC handle, a Kafka client is).
All of them are matched by fully qualified name, which is why this library has no framework
dependencies of its own and works against whatever versions a service uses - add your own
`@AggregateRoot` meta-annotation or an internal SDK fork and the rules follow.

### In code

```java
@AnalyzeArchitecture(packagesOf = OrdersApplication.class)
class ArchitectureTest extends ArchitectureRulesTest {

    @Override
    protected void customize(ArchitectureRulesConfiguration.Builder builder) {
        builder.conventions(conventions -> conventions
                        .packages(PackageRole.CONTROLLER, "..api.web..")
                        .addPackages(PackageRole.ENTITY, "..persistence.jpa..")
                        .addAnnotations(AnnotationRole.PERSISTENT_TYPE, "com.acme.ddd.AggregateRoot"))
                .addRuleSet(new AcmePlatformRules());
    }
}
```

### In `architecture-rules.properties` (test classpath)

```properties
architecture.rules.base-packages = com.acme.orders

# toggles: a group id, a rule id, or *
architecture.rules.rules.kafka = false
architecture.rules.rules.domain-isolation = true
architecture.rules.rules.web.controllers-do-not-call-controllers = false

# conventions: replace a role's values, or add to them with the .add suffix
architecture.rules.conventions.packages.controller = ..api.web..
architecture.rules.conventions.packages.entity.add = ..persistence.jpa..
architecture.rules.conventions.libraries.aws-sdk.add = com.acme.storagesdk..
architecture.rules.conventions.annotations.persistent-type.add = com.acme.ddd.AggregateRoot

# the names only this organisation can supply
architecture.rules.conventions.types.base-exception = com.acme.common.ApplicationException
architecture.rules.conventions.types.base-entity = com.acme.common.jpa.AuditableEntity
architecture.rules.conventions.types.event-publisher = com.acme.messaging.EventPublisher
architecture.rules.conventions.settings.rest-base-path-pattern = /api/v\\d+(/.*)?

# severity: reported everywhere, but does not fail the build
architecture.rules.severity.modules = warning

# reports
architecture.rules.service-name = orders-service
architecture.rules.report.console = true
architecture.rules.report.color = auto
architecture.rules.report.json = true
architecture.rules.report.json-file = target/architecture-report.json
architecture.rules.report.max-violations-per-rule = 5

# per-module deviations; the label after 'module.' is free-form
architecture.rules.module.legacy.packages = com.acme.orders.legacy
architecture.rules.module.legacy.rules.layering = false
architecture.rules.module.legacy.conventions.packages.entity.add = ..jpa..
```

An unknown key under `architecture.rules.` fails the build instead of being ignored: a typo in a
property name would otherwise switch nothing off and be discovered only when the rule fires.

Settings layer in a fixed order - properties file, then the annotation, then `customize` - so a
platform team can template the properties file and a service can still override one toggle locally.

### How a toggle is resolved

The most specific selector wins: a rule id beats a group id, which beats `*`. Enabling a whole group
never activates the stricter *opt-in* rules inside it - those have to be named:

```java
.disable("web")                                   // the whole group off
.enable("web.controllers-do-not-expose-entities")  // except this one
.enable("storage.adapters-do-not-expose-sdk-types") // an opt-in rule, on
```

## Severity: enforce, warn, or neither

Between "this rule fails the build" and "this rule is off" there is a third setting a large estate
needs: report it, everywhere, but let the build through while the debt is worked down.

```java
builder.warnOn("modules", "web.controllers-do-not-call-controllers")
       .failOn("modules.internals-are-not-accessed-from-other-modules");
```

```properties
architecture.rules.severity.modules = warning
```

A warned rule is evaluated like any other. Its violations appear in the console report under
`WARNING` and in the JSON with `"severity": "warning"`, so the org-wide dashboard can see what a
service has chosen to tolerate - a disabled rule disappears, a warned one does not. In JUnit the test
is *aborted* with the violation message rather than failed, which surfaces as a skipped test carrying
its reason instead of a silent pass.

## Reports

Every run emits two reports, from the same result, for two different readers.

**Console** - ranked and colourised, failures first, a few violations per rule with the file and line,
and the fix:

```
------------------------------------------------------------------------------
 Architecture rules | catalog-service
 39 rules | 37 passed | 2 failed | 6 violations | 156 ms
------------------------------------------------------------------------------

 FAILED   web.controllers-do-not-expose-entities (3 violations)
   Controllers do not expose JPA entities
   - Method <...ProductController.get(java.util.UUID)> has ProductEntity in its signature in (ProductController.java:66)
   ... 2 more (see the JSON report)
   Fix: Return a DTO instead of the entity and map between them (a MapStruct mapper in the web
        package). Serialising an entity publishes the database schema as an API contract ...

 passed | layering 2, cycles 6, persistence 5, spring 5, ...
 JSON report: /workspace/orders/target/architecture-report.json
```

Colour follows `NO_COLOR` and `TERM=dumb` by default (`report.color = auto|always|never`), and the
console never prints more than `report.max-violations-per-rule` violations per rule.

**JSON** - the whole run, untruncated, at `target/architecture-report.json`:

```json
{
  "schemaVersion": "1.0.0",
  "tool":    { "name": "ru.ludwigandreas:architecture-rules", "version": "1.0.0" },
  "service": { "name": "catalog-service", "basePackages": ["com.acme.orders"], "modules": ["..."] },
  "generatedAt": "2026-02-01T10:00:00Z",
  "durationMillis": 156,
  "summary": { "rules": 39, "passed": 37, "failed": 2, "warnings": 0, "violations": 6,
               "violationsByGroup": { "web": 4, "persistence": 2 } },
  "rules": [
    {
      "id": "web.controllers-do-not-expose-entities",
      "group": "web", "scope": "service",
      "severity": "error", "status": "violated",
      "description": "Controllers do not expose JPA entities",
      "remediation": "Return a DTO instead of the entity and map between them ...",
      "durationMillis": 12,
      "violationCount": 3,
      "violations": [
        { "message": "Method <...get(java.util.UUID)> has ProductEntity in its signature ...",
          "class": "com.acme.orders.web.ProductController",
          "member": "get",
          "sourceFile": "ProductController.java",
          "line": 66,
          "location": "ProductController.java:66" }
      ]
    }
  ]
}
```

Three things in that shape are deliberate:

- **Rules that passed are included.** An aggregator has to be able to tell "this service checks the
  Kafka rules and is clean" from "this service does not check them at all". Without that, a dashboard
  rewards switching rules off.
- **Identity travels with the results.** Service name, base packages, modules, schema version and tool
  version are in every file, so reports collected from many repositories can be pooled without a
  naming convention holding them together, and an old report stays interpretable after the schema
  moves on.
- **Each rule carries its own fix.** `remediation` sits next to the violations, and each violation
  carries class, member, file and line. A coding agent handed the file has everything it needs to make
  the change - no access to this library's source required - and a PR bot can turn a violation into an
  inline comment.

Configure with `report.*` properties or in code:

```java
builder.serviceName("orders-service")
       .reporting(report -> report.jsonFile(Path.of("build/reports/architecture.json"))
                                  .maxViolationsPerRule(10)
                                  .color(ColorMode.NEVER));
```

Outside JUnit - a Gradle task, a CI step, a `main()` - the same run is one call:

```java
ArchitectureRules.checkAndReport(configuration);   // evaluates, writes both reports, throws on failures
ArchitectureReport report = ArchitectureRules.run(configuration);  // same, without throwing
```

The JSON is written by a hand-rolled writer, not Jackson: this library stays dependency-free beyond
ArchUnit, so nothing is added to a consuming service's test classpath for the sake of one file.

## What this library deliberately does not check

Some checks look like they belong here and do not. They were considered and rejected, and the reasons
are recorded so nobody re-derives them:

| Not checked | Why, and where it belongs |
|---|---|
| ASCII-only / no-Cyrillic source content | ArchUnit reads compiled bytecode structure, not source text, string literals or comments. Checkstyle (`RegexpSinglelineJava`, `RegexpMultiline`) |
| "Lombok was used instead of hand-written boilerplate" | A Lombok-generated method and a hand-written one are identical bytecode; the annotation processor's input is not visible. Not implementable here - the immutability rule asks about the resulting API instead, which is why *it* works |
| Ad-hoc object mapping written inline in a service | No structural signal separates legitimate conversion code from "should have been MapStruct". A code-review judgment, not a bytecode pattern |
| Methods returning `null` instead of `Optional` | Dataflow analysis across method bodies. Sonar, NullAway, ErrorProne. The `optional` group checks declared types only |
| Naming, formatting and security rules | Already covered by Checkstyle, SonarQube and SCM policy. This library stays on structure and dependencies |

The two scope limits are stated in code as well, on `OptionalUsageRules` and `MapperConventionRules`,
so the next person to open the class finds the boundary before extending it.

## Per-module rules

A monolith rarely deviates everywhere at once - usually one legacy module is behind, or two payment
modules need something stricter. That is a first-class concept rather than a second test class:

```java
builder.customizeModules(ModuleRuleCustomization.forModules("com.acme.orders.legacy")
                .conventions(conventions -> conventions.addPackages(PackageRole.ENTITY, "..jpa.."))
                .disable("layering.controllers-do-not-access-persistence")
                .build())
       .customizeModules(ModuleRuleCustomization.forModules("com.acme.payments", "com.acme.payouts")
                .enable("domain-isolation")
                .addRuleSet(new PciAuditRules())
                .build());
```

A customization *takes over* a rule when it redefines the conventions the rule is built from, or when
its selection names the rule. A taken-over rule is then built twice: once service-wide, scoped to
exclude the customizing modules, and once per customization with that module's conventions and
toggles. Every other rule stays a single service-wide instance covering the module too. The effect is
that each class is checked by exactly one instance of each rule - a module can genuinely deviate, and
nothing is reported twice.

## Adding your own rules

Implement `ArchitectureRuleSet` and derive every package name from the `RuleContext` you are handed,
so the rule set stays reusable across services with different layouts. The predicates and conditions
the built-in rules are written with are published for reuse in
`ru.ludwigandreas.archrules.support`.

```java
public final class AcmePlatformRules implements ArchitectureRuleSet {

    @Override
    public RuleGroup group() {
        return RuleGroup.CUSTOM;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(ArchitectureRule.of(
                RuleId.of(RuleGroup.CUSTOM, "clocks-are-injected"),
                ArchRuleDefinition.noClasses()
                        .that(ConventionPredicates.services(context))
                        .should(ArchitectureConditions.notCallMethods(
                                Map.of("java.time.LocalDateTime", Set.of("now")),
                                "LocalDateTime.now - inject a Clock"))));
    }
}
```

Register it per service (`builder.addRuleSet(...)`), per module (`ModuleRuleCustomization.addRuleSet`)
or organisation-wide by listing it in
`META-INF/services/ru.ludwigandreas.archrules.ArchitectureRuleSet` - a jar on the test classpath then
contributes its rules to every service that depends on it, with no test code to change. Set
`builder.includeBuiltInRuleSets(false)` to use this library purely as the execution and configuration
harness for rules of your own.

## Adopting it on an existing codebase

A service with history will not pass everything on day one. Note first that the groups checking
against a shared base type (`exceptions`, `entity-base`, `kafka-contracts`) are on by default and will
ask to be configured as soon as the service has an entity, a custom exception or a Kafka producer -
that is the one upgrade step this library asks for, and the message names the property. Three ways
in:

- **Warn first.** `architecture.rules.severity.<selector> = warning` keeps a rule evaluated and
  reported while the build stays green. Unlike disabling it, the violations remain visible in the JSON
  report, so the debt is tracked rather than forgotten.
- **Freeze.** `architecture.rules.freeze = true` records today's violations as the accepted baseline
  (ArchUnit's `FreezingArchRule`) and fails only on new ones, so the codebase can improve
  incrementally without ever regressing. Commit the violation store.
- **Disable and file the debt.** Switch off the rules you cannot satisfy yet, by id, with a comment
  naming the ticket. A disabled rule is visible in configuration; an unwritten rule is not.

## Notes on the design

- **One test per rule.** A dynamic test per rule means a newly failing rule shows up as a newly
  failing test rather than a longer message, and the test name is exactly the string to disable it
  with. Surefire's XML names dynamic tests by index, so the rule id is also prefixed onto every
  failure message; add `usePhrasedTestCaseMethodName` to have Surefire use the display name:
  ```xml
  <plugin>
      <artifactId>maven-surefire-plugin</artifactId>
      <configuration>
          <statelessTestsetReporter implementation="org.apache.maven.plugin.surefire.extensions.junit5.JUnit5Xml30StatelessReporter">
              <usePhrasedTestCaseMethodName>true</usePhrasedTestCaseMethodName>
          </statelessTestsetReporter>
      </configuration>
  </plugin>
  ```
- **Confinement rules do not need a home package.** "Only the storage adapters may see the AWS SDK"
  still holds for a service that configures no storage package - it simply has nowhere the SDK would
  be allowed. Only the rules that *place* a class (entities in the entity packages, consumers in the
  messaging packages) are skipped when the target role is not configured.
- **No framework dependencies.** Spring, JPA, Kafka and the AWS SDK are referenced by name, never by
  class literal, so this jar drags nothing onto a service's test classpath and cannot conflict with
  its versions. The only dependency is `archunit` itself (and JUnit, optional, for the base class).
- **Own code only.** Package roles are matched inside the analysed base packages, because
  `..persistence..` also matches `jakarta.persistence` and `..web..` matches
  `org.springframework.web.bind.annotation`. Third-party technologies are matched through their own
  configurable library packages instead.
- **Production classes only.** The default import excludes test sources - otherwise the
  test/production separation rule could not mean anything. `architecture.rules.include-tests = true`
  widens it.
- **Empty is not a failure - but an empty analysis is.** `allowEmptyShould` defaults to true, which
  is what lets a service keep the Kafka rules enabled before it has any Kafka code. Importing *no
  class at all* is refused instead: a mistyped base package would otherwise let every rule pass
  against an empty class set and report a green architecture test for a service nothing was checked
  in. Call `allowEmptyAnalysis(true)` if an empty module really is expected.
- **The import is cached** per (packages, import options) for the lifetime of the JVM, so several
  architecture test classes in one build scan the bytecode once. They do, however, share the default
  report path - a module with more than one architecture test class should give each its own
  `report.json-file`, or the last one to run wins.
- **Severity applies everywhere.** A rule downgraded to `warning` is skipped by `check()`, aborted
  rather than failed in JUnit, and still present in both reports. Severity that only applied to one
  entry point would be a trap.

## Testing

`mvn -pl architecture-rules test` runs the rules against fixture services in `src/test/java`: one
that satisfies every rule and one per group that breaks it on purpose. The reports are tested the same
way - the JSON is parsed back with a real parser, because a report an aggregator cannot read is worse
than no report. A rule that only ever passes
proves nothing, so each is asserted from both sides. The frameworks the fixtures use are stubs
declared under their real package names - which is also what proves the name-based matching works.

The reference service in [`crud-service-example`](../crud-service-example) enables the library on
itself, so this repository's own example proves compliance on every build.
