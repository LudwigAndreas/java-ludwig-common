# ludwig-service-parent

***English** · [Русский](README.ru.md)*

Every build decision a microservice on this platform makes, in one `<parent>`. The compiler and its
annotation processors in the order Lombok, MapStruct and QueryDSL require; surefire and failsafe split
by phase; an enforced coverage gate; the enforcer gate; Checkstyle; and a fully configured jib that
never runs unless it is asked to. It imports [`ludwig-bom`](../ludwig-bom/README.md), so this one
element covers versions too.

```xml
<parent>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>ludwig-service-parent</artifactId>
    <version>1.1.0</version>
    <relativePath/>
</parent>

<groupId>com.example</groupId>
<artifactId>my-service</artifactId>
<version>0.1.0</version>

<dependencies>
    <dependency>
        <groupId>ru.ludwigandreas</groupId>
        <artifactId>web-core-spring-boot-starter</artifactId>
    </dependency>
</dependencies>
```

No version on any dependency, and nothing else to copy.

> **`<relativePath/>` must be present and empty.** Omit it and Maven looks for `../pom.xml`, which in
> a standalone service does not exist — or, worse, is an unrelated POM — and the build fails with a
> confusing *non-resolvable parent*.

---

## Contents

- [Who inherits this, and who does not](#who-inherits-this-and-who-does-not)
- [Everything is overridable](#everything-is-overridable)
- [What you get](#what-you-get)
- [The one thing you opt into](#the-one-thing-you-opt-into)
- [Building an image](#building-an-image)
- [Releasing](#releasing)

---

## Who inherits this, and who does not

**A service inherits this file. A library does not.**

The library modules in this repository — `db-core`, `web-core-spring-boot-starter`, the rest — keep
`ru.ludwigandreas:common` as their parent and import `ludwig-bom` directly. That looks like an
oversight and is not, for two reasons:

- a library must stay usable by a service running a **different** Spring Boot line, and a library
  built against `spring-boot-starter-parent` bakes this parent's Boot baseline into its own build;
- a library has no image, no repackaged jar and no service-shaped test layout, so most of what this
  file configures would be dead weight.

The rule: **anything that ships as a container inherits this file; anything that ships as a jar for
someone else to depend on does not.**

---

## Everything is overridable

Every property this file declares is meant to be redeclared. Inheritance resolves child-first, so a
service's own `<properties>` win:

```xml
<properties>
    <!-- This service is mostly glue; the platform default of 0.70 would be theatre. -->
    <jacoco.instruction.coverage.minimum>0.50</jacoco.instruction.coverage.minimum>
    <ludwig.image.registry>registry.eu.example.internal</ludwig.image.registry>
</properties>
```

No service ever needs to fork this file to move a version, relax a threshold or switch a plugin off.
If you find yourself wanting to, that is a bug in this file — say so rather than forking, because a
fork stops receiving every later fix.

---

## What you get

### Compiler and annotation processors

**Order is load-bearing.** Three processors run in one compilation and the sequence is not
negotiable:

1. **Lombok** generates the entity accessors and constructors;
2. **`lombok-mapstruct-binding`** sits between Lombok and MapStruct — it is what makes MapStruct see
   Lombok-generated accessors. Without it, in this position, MapStruct runs first and maps nothing;
3. **QueryDSL's `JPAAnnotationProcessor`** generates the Q-types every query is written against,
   which is what makes those queries compile-time checked;
4. **MapStruct** generates the layer-to-layer mappers;
5. **`spring-boot-configuration-processor`** produces the metadata that makes your `*Properties`
   autocomplete in an IDE.

Versions come from `ludwig-bom` via `annotationProcessorPathsUseDepMgmt`, not from a `<version>` on
every `<path>`. Before that flag existed, every module repeated `${lombok.version}`, which is how the
Lombok used to *compile* a module drifts from the Lombok whose processor *generates* its code.

`-parameters` is on: Spring reads parameter names to bind a `@RequestParam` without repeating it in
the annotation, and a parameter-level constraint violation can only name the parameter it rejected if
they are present — without it, a 400 reports the offending input as `arg0`.

### Tests, split by phase

| Plugin | Runs | Matches |
|---|---|---|
| surefire | `test` | everything **except** `*IT` and `*IntegrationTest` |
| failsafe | `integration-test` / `verify` | `*IT`, `*IntegrationTest` |

Integration tests run **after** the artifact is packaged, and a failing one does not stop the build
before the package exists. `*IntegrationTest` is excluded by name because this platform's services
already use that convention — renaming their test classes is not a precondition for getting the phase
right.

Failsafe reports failures at `verify` rather than throwing at `integration-test`, so
`post-integration-test` teardown always runs.

### The coverage gate

JaCoCo instruments both phases, **merges** the two execution files, and checks the merged result:

| Counter | Minimum |
|---|---|
| `INSTRUCTION` | 0.70 |
| `BRANCH` | 0.60 |

Merging is the part that matters. Checking unit coverage alone punishes a service whose behaviour is
genuinely proven by integration tests — which, for anything with a queue or a broker in it, is most
of the behaviour worth proving.

### The enforcer gate

Runs at `validate`, so it fails before anything is compiled:

- **`requireMavenVersion`** / **`requireJavaVersion`** — the baseline this platform is built against;
- **`dependencyConvergence`** — two versions of the same artifact reaching the classpath by different
  paths is the single most common cause of a `NoSuchMethodError` that only appears in production.
  Resolve it with an entry in `ludwig-bom`, **not** by muting the rule;
- **`banDuplicatePomDependencyVersions`** — the same `groupId:artifactId` listed twice in one POM: the
  second silently wins and the first is a lie.

### Checkstyle

Wired at `validate` against [`checkstyle-rules`](../checkstyle-rules/README.md), resolved from the
repository like any other artifact — not from a path in somebody's checkout. The configuration's
formatting rules mirror IntelliJ IDEA's defaults one for one, so *Reformat Code* always produces a
build-clean file.

---

## The one thing you opt into

Checkstyle is the single plugin a service has to declare, with no configuration:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-checkstyle-plugin</artifactId>
        </plugin>
    </plugins>
</build>
```

Activating it in this parent would make *this POM* resolve `checkstyle-rules` for sources it does not
have, which is why it is the one exception to "one parent and nothing else".

---

## Building an image

jib is fully configured and **bound to nothing** by default, so an ordinary `mvn install` never builds
an image and never contacts a registry.

```bash
mvn package jib:dockerBuild      # into the local Docker daemon
mvn deploy -Pci                  # straight to the registry, no daemon required
```

The `ci` profile binds `jib:build` to `deploy`. It pushes layers to the registry over HTTPS, so a CI
agent needs no Docker daemon, no privileged container and no socket mounted into the build.
Credentials come from the agent, never from a POM.

**The base image is pinned by name, version and digest**
(`eclipse-temurin:21.0.12_8-jre@sha256:…`). A tag alone can be re-pointed at different content, so a
build would silently change what it ships. Override `ludwig.image.base` if you must — with a digest.

| Property | Default |
|---|---|
| `ludwig.image.registry` | `registry.example.internal` |
| `ludwig.image.namespace` | `ludwig` |
| `ludwig.image.name` | `${registry}/${namespace}/${project.artifactId}` |
| `ludwig.image.tag` | `${project.version}` |
| `ludwig.image.user` | `1000:1000` — never root |
| `ludwig.image.jvm.flags` | `-XX:MaxRAMPercentage=75.0 -XX:InitialRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError` |

`MaxRAMPercentage` rather than a fixed `-Xmx`, because the container's limit is set by the
orchestrator and a hardcoded heap is wrong the moment that limit changes.
`ExitOnOutOfMemoryError` makes a heap exhaustion a restart instead of a pod that stays *Ready* and
serves errors.

Ports 8080 and 8081 are exposed — the second for a separated actuator port. OCI labels carry the
title, version, source and, from `git-commit-id-maven-plugin`, the **commit** the image was built
from, which is the only reliable way back from a running container to the code inside it.

jib's `creationTime` and `filesModificationTime` defaults are left alone deliberately: they are what
make an image reproducible, and overriding them to "now" is how two builds of identical source produce
different digests.

---

## Releasing

```bash
mvn -Prelease -Drevision=1.2.0 deploy
```

The `release` profile adds two enforcer rules:

- **`requireReleaseDeps`** — a release that depends on a `SNAPSHOT` is not reproducible: the thing it
  was tested against can be replaced afterwards;
- **`requireReleaseVersion`** — the artifact itself must not be a snapshot. This is the check that
  catches `-Prelease` without `-Drevision=`.

`flatten-maven-plugin` runs at `process-resources` so the POM that is actually installed carries a
concrete version rather than the literal `${revision}`, which nothing downstream could resolve.

---

## Taking a newer BOM without new build config

`ludwig-bom` and this file are separate artifacts because they pull in opposite directions: *"all
versions in one place"* wants a single BOM everyone tracks, while *"one parent for every service"*
means a change as small as a jib label forces a parent bump. Keeping them separate lets a service take
one without the other:

```xml
<parent>
    <artifactId>ludwig-service-parent</artifactId>
    <version>1.1.0</version>
    <relativePath/>
</parent>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>ru.ludwigandreas</groupId>
            <artifactId>ludwig-bom</artifactId>
            <version>1.3.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

A nearer import wins, so the newer BOM's versions apply while the build configuration stays where it
was.
