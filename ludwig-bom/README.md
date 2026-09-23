# ludwig-bom

***English** · [Русский](README.ru.md)*

The platform's version registry: every `ru.ludwigandreas` module at a single version, plus every
third-party version the platform pins on top of what Spring Boot manages. Import it and name no
versions.

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>ru.ludwigandreas</groupId>
            <artifactId>ludwig-bom</artifactId>
            <version>1.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependencies>
    <dependency>
        <groupId>ru.ludwigandreas</groupId>
        <artifactId>db-core</artifactId>
    </dependency>
    <dependency>
        <groupId>com.querydsl</groupId>
        <artifactId>querydsl-jpa</artifactId>
        <classifier>jakarta</classifier>
    </dependency>
</dependencies>
```

A service inheriting [`ludwig-service-parent`](../ludwig-service-parent/README.md) gets this BOM
automatically and **should not import it a second time**.

---

## What it is, and what it deliberately is not

`<packaging>pom</packaging>`, one `<dependencyManagement>` block, and nothing else. **No `<build>`, no
`<plugins>`, no `<modules>`.** This artifact answers exactly one question — *which version* — and a
consumer importing it should not thereby acquire an opinion about how to compile, test or package
anything. That is what the parent is for, and the two are separate artifacts precisely so a service
can take one without the other.

**The published POM has no `<parent>` element.** Inside this repository the file has one, purely so it
can inherit the reactor's shared properties and the flatten execution; `flatten-maven-plugin` runs in
`bom` mode here, so the POM installed and deployed under these coordinates is parentless and every
version in it is a literal. That matters: a consumer importing this BOM must not be forced to resolve
`ru.ludwigandreas:common` from a repository it may not have.

---

## What it pins

### The platform's own modules

Every module in this repository, all at `${project.version}` — which the flattened POM turns into a
literal, so a consumer never sees an unresolved expression:

`common-utils` · `db-core` · `web-core-spring-boot-starter` · `odata-filter-spring-boot-starter` ·
`outbox-spring-boot-starter` · `security-spring-boot-starter` ·
`identity-projection-spring-boot-starter` · `hot-reload-spring-boot-starter` ·
`observability-spring-boot-starter` · `user-settings-spring-boot-starter` · `architecture-rules` ·
`checkstyle-rules`

### Third-party versions Spring Boot does not manage, or manages differently

| Area | Artifacts |
|---|---|
| Code generation | Lombok, `lombok-mapstruct-binding`, MapStruct, QueryDSL (`core`, `jpa:jakarta`, `apt:jakarta`), `jakarta.persistence-api` |
| OData | Olingo `odata-server-api`, `odata-server-core` |
| Resilience & config | Resilience4j, Spring Vault, FreeMarker |
| Messaging | `spring-kafka`, `spring-kafka-test` |
| Observability | `opentelemetry-api-incubator` |
| API docs | springdoc OpenAPI |
| Testing | Testcontainers (as a nested BOM import), ArchUnit, GreenMail, `jakarta.el` |

Two entries carry a warning in the POM and are worth repeating here:

**Lombok is pinned ahead of Spring Boot's version, deliberately.** Boot's managed 1.18.34 fails on
current JDK 17 patch releases with `ExceptionInInitializerError: com.sun.tools.javac.code.TypeTag ::
UNKNOWN` — Lombok reaches into javac internals and needs a build that knows the compiler it is running
under. Do not "align" it back.

**`opentelemetry-api-incubator` must track the `opentelemetry.version` Spring Boot manages.** An
incubator API a minor version ahead of the SDK it is incubating for is a `NoSuchMethodError` waiting
to happen.

---

## Ordering is load-bearing

Maven resolves a managed version by taking the **first** matching entry in the effective
`dependencyManagement`, and an imported BOM is expanded *in place*, at the position of its
`<dependency>` element. So this file is arranged as:

1. the platform's own modules and its explicit third-party overrides — **first, and therefore
   winning**;
2. `testcontainers-bom` and `spring-boot-dependencies` — **last**, filling in everything else.

This is why the platform's overrides (Lombok, Testcontainers, FreeMarker) do not need the
property-override trick that only works when you inherit `spring-boot-starter-parent`. This BOM does
not inherit it, so the overrides are stated as entries instead.

Moving an import above an explicit entry silently reverses which version wins. If you edit this file,
keep the sections in order.

---

## Overriding a version in a service

Redeclaring the property alone does **not** work:

```xml
<!-- Does nothing. -->
<properties>
    <mapstruct.version>1.6.0</mapstruct.version>
</properties>
```

A property set in a consumer does not reach into an imported BOM's already-interpolated entries — by
the time the import happens, `${mapstruct.version}` has been resolved to whatever this file said.
Override both:

```xml
<properties>
    <mapstruct.version>1.6.0</mapstruct.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
        <version>${mapstruct.version}</version>
    </dependency>
</dependencies>
```

Or declare your own `dependencyManagement` entry *before* the import, since first wins.

---

## The classified artifacts

A managed entry is keyed on `groupId:artifactId:type:classifier`, so QueryDSL's Jakarta variants are
managed separately:

```xml
<dependency>
    <groupId>com.querydsl</groupId>
    <artifactId>querydsl-jpa</artifactId>
    <classifier>jakarta</classifier>
</dependency>
```

Without the classified entries, `querydsl-jpa:jakarta` would be unmanaged and the version would have
to be repeated at every use site — which is exactly the drift this file exists to prevent.

---

## Two artifacts, one release train

`ludwig-bom` and `ludwig-service-parent` are separate on purpose. They pull in opposite directions:
*"all versions in one place"* wants a single BOM everyone tracks, while *"one parent for every
service"* means a change as small as a jib label forces a parent bump that every service eventually
has to adopt.

Keeping them separate lets a service take a **new BOM without new build config**, or the reverse — see
[the parent's README](../ludwig-service-parent/README.md#taking-a-newer-bom-without-new-build-config).

---

## Verifying it

The property that matters — that this BOM resolves with nothing else present — is not something a
reactor build can check, because inside the reactor everything is present. It is verified by resolving
the published BOM from a scratch project outside this repository. If you change the flatten mode or
add a `<parent>`-dependent expression, do that check by hand.
