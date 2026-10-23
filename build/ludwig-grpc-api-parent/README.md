# ludwig-grpc-api-parent

***English** · [Русский](README.ru.md)*

The parent POM of every `<service>-grpc-api` contract module. It supplies the gRPC and protobuf dependencies,
[`grpc-core`](../../sources/grpc-core) (so a contract can import `ludwig/operation/v1/operation.proto`), and
protobuf code generation, so that a contract module's POM is a parent, an `artifactId`, a name and a description.

| | |
|---|---|
| Role | contract parent (POM) |
| Depends on (in-repo) | [`grpc-core`](../../sources/grpc-core); parent: reactor root `common`; imports `ludwig-bom` |
| Used by | every `*-grpc-api` module (currently [`catalog-grpc-api`](../../sources/catalog-grpc-api)) |
| Surfaces | none |

## Usage

Create `sources/<service>-grpc-api/pom.xml`:

```xml
<parent>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>ludwig-grpc-api-parent</artifactId>
    <version>${revision}</version>
    <!-- Required: without it Maven silently resolves a stale parent from ~/.m2. -->
    <relativePath>../../build/ludwig-grpc-api-parent/pom.xml</relativePath>
</parent>
<artifactId>billing-grpc-api</artifactId>
<name>Billing gRPC API</name>
<description>The gRPC contract of billing.</description>
```

Put `.proto` files under `src/main/proto/ludwig/<service>/v<N>/`. Then register the module:

1. `<module>sources/<service>-grpc-api</module>` in the root `pom.xml`;
2. a `<dependency>` entry in `build/ludwig-bom/pom.xml`;
3. a dependency in `build/jacoco-aggregate/pom.xml`;
4. `scripts/manifest.sh build`.

The proto rules (package, `java_package`, `java_multiple_files`, `idempotency_level` on every rpc) are in
[grpc-core](../../sources/grpc-core#writing-a-contract-module) and checked by `scripts/check_protos.sh`.

**A contract module declares no dependencies, plugins or `dependencyManagement`** and contains no Java
(`pom-topology` capability). If it seems to need any, it is not only a contract.

## Configuration

| What the parent provides | Value |
|---|---|
| Dependencies | `io.grpc:grpc-protobuf`, `io.grpc:grpc-stub`, `com.google.protobuf:protobuf-java`, `ru.ludwigandreas:grpc-core` (versions from `ludwig-bom`) |
| Generation | profile `ludwig-grpc-contract`, active when `${basedir}/src/main/proto` exists; runs `io.github.ascopes:protobuf-maven-plugin` |
| Build decisions | none of its own: inherited from the reactor root like any library (Checkstyle, JaCoCo, revapi, enforcer) |

`protoc` is a Maven artifact at `protoc.version` (root POM), which must equal `protobuf.version` in `ludwig-bom`.

## Deployment

Published with every release like the other library POMs. Contract jars are consumed as dependencies by servers
and clients.

## Observability

Not applicable (build artifact).

## Audit

Not applicable.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| `manifest.sh layout` fails naming a `-grpc-api` module | Wrong parent, missing registration, Java sources, or no `.proto` | Fix as the message says |
| Contract jar is empty | No `src/main/proto` (generation profile not active) | Add the protos; `layout` refuses such a module anyway |
| `check_protos.sh` reports a version mismatch | `protoc.version` ≠ `protobuf.version` | Align the root POM property with the BOM |

## Testing

No tests of its own; `scripts/manifest.sh layout` and `scripts/check_protos.sh` (both in the gate) validate every
module using it.

## Design decisions

- **A parent rather than a generator script**: what every contract needs is stated once and inherited; the few
  lines left per module are checked by the gate.
- **Parented by the reactor root**: a contract is a published library and gets exactly the library build.
- **`grpc-core` always on the path**: it puts `operation.proto` on the import path, and every consumer already
  depends on it through a starter.
