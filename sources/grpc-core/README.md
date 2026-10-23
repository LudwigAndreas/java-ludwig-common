# grpc-core

***English** · [Русский](README.ru.md)*

What two platform services must agree on when they talk over gRPC, with no transport attached: the metadata
keys, the codec between RFC 9457 problems and `google.rpc.Status`, the long-running-operation message, the
context bridge that restores thread-locals around every gRPC callback, and MapStruct configuration for
protobuf messages. Both gRPC starters depend on it; a service may carry only one of them.

| | |
|---|---|
| Role | library |
| Depends on (in-repo) | [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Used by | `grpc-client`, `grpc-server` starters; [`ludwig-grpc-api-parent`](../../build/ludwig-grpc-api-parent) |
| Surfaces | none (defines `ludwig/operation/v1/operation.proto`) |

```
ru.ludwigandreas.grpc.core
├── metadata    GrpcMetadataKeys
├── problem     GrpcStatusCodes, GrpcStatusCodec, DecodedProblem
├── operation   OperationMessages
├── context     GrpcCallContext, ThreadBinding, ContextRestoringServerInterceptor
└── mapping     ProtobufMapperConfig, ProtobufConversions

ru.ludwigandreas.operation.grpc.v1   generated from ludwig/operation/v1/operation.proto
```

## Usage

Services do not depend on this module directly; they get it through a gRPC starter or a contract module.

### Writing a contract module

A service's gRPC contract is its own module `sources/<service>-grpc-api`, parented by
[`ludwig-grpc-api-parent`](../../build/ludwig-grpc-api-parent), holding only `.proto` files and generated code.
Callers depend on that jar. The whole POM:

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

plus three registrations: a `<module>` in the root `pom.xml`, an entry in `build/ludwig-bom/pom.xml`, and a
dependency in `build/jacoco-aggregate/pom.xml`. `scripts/manifest.sh layout` (run by the gate) fails naming the
module if any is missing, the parent differs, the module has Java, or it has no `.proto`.

```proto
syntax = "proto3";

package ludwig.billing.v1;

option java_package = "ru.ludwigandreas.billing.grpc.v1";
option java_multiple_files = true;

service BillingService {
  rpc GetInvoice(GetInvoiceRequest) returns (Invoice) {
    option idempotency_level = NO_SIDE_EFFECTS;
  }
  rpc CreateInvoice(CreateInvoiceRequest) returns (Invoice) {
    option idempotency_level = IDEMPOTENCY_UNKNOWN;
  }
}
```

`scripts/check_protos.sh` (run by the gate) enforces:

| Rule | Why |
|---|---|
| `package ludwig.<service>.v<N>` | `v2` can be served beside `v1` |
| `java_package = ru.ludwigandreas.<service>.grpc.v<N>` | The single coverage/analysis exclusion for generated code (`**/grpc/v*/**`) |
| `java_multiple_files = true` | Renaming the file must not rename every type |
| every `rpc` declares `idempotency_level` | The gRPC client decides retries from it; `IDEMPOTENCY_UNKNOWN` is a valid answer, silence is not |
| `.proto` only in a `-grpc-api` module (or here) | Callers must not depend on a jar built as an image |
| `protoc.version` (root POM) = `protobuf.version` (BOM) | Generated code matches the runtime |

`protoc` is resolved as a Maven artifact; no system compiler. **Not checked:** whether an `idempotency_level` is
true (only the author knows), and wire compatibility of a changed proto (needs a per-release descriptor baseline;
see [architecture-rules](../../build/architecture-rules)). Review both.

### Mapping messages

```java
@Mapper(config = ProtobufMapperConfig.class)
public interface ProductMessageMapper {
    ru.ludwigandreas.catalog.grpc.v1.Product toMessage(Product product);
}
```

`ProtobufMapperConfig` leaves a field unset for an absent value (builder setters throw on `null`), fills repeated
fields through adders, and converts `Instant`/`Duration` ↔ `google.protobuf.Timestamp`/`Duration`
(`ProtobufConversions`). The protobuf MapStruct SPI (on the annotation-processor path from
`ludwig-service-parent`) handles builders, `hasX` presence and `ENUM_NAME_` prefixes. Worked example:
`crud-service-example`'s `ProductMessageMapper`.

### Metadata keys

`GrpcMetadataKeys` declares each key once; each is the lower-cased HTTP header carrying the same value.

| Key | Mirrors |
|---|---|
| `authorization` | HTTP `Authorization` |
| `x-correlation-id` | `ludwig.observability.correlation.header-name` (`forHeader` if renamed) |
| `idempotency-key` | `IdempotencyHeaders.KEY` |
| `idempotency-replayed` | `IdempotencyHeaders.REPLAYED` |
| `accept-language` | HTTP `Accept-Language` |
| `x-time-zone` | web-core's time zone header |
| `retry-after` | `OperationHeaders.RETRY_AFTER` |

The owning modules are not dependencies here, so the literals are repeated; the starters carry tests asserting
equality.

### Problems as gRPC status

Which problem an exception is stays in web-core's `ProblemMapperRegistry`; `GrpcStatusCodec` changes only the
envelope. **No module writes a gRPC-specific exception mapping.**

| Problem status | gRPC code | | Problem status | gRPC code |
|---|---|---|---|---|
| 400, 405, 406, 415 | `INVALID_ARGUMENT` | | 413, 429 | `RESOURCE_EXHAUSTED` |
| 401 | `UNAUTHENTICATED` | | 500 | `INTERNAL` |
| 403 | `PERMISSION_DENIED` | | 501 | `UNIMPLEMENTED` |
| 404, 410 | `NOT_FOUND` | | 502, 503 | `UNAVAILABLE` |
| 409, 423 | `ABORTED` | | 504 | `DEADLINE_EXCEEDED` |
| 412, 422 | `FAILED_PRECONDITION` | | | |

| Problem member | Travels as |
|---|---|
| status | the code above, exact number in `ErrorInfo.metadata[http_status]` |
| `code` | `ErrorInfo.reason` |
| type, title | `ErrorInfo.metadata[type]`, `[title]` |
| detail | status message and `LocalizedMessage` with its locale |
| `violations` | `BadRequest.field_violations` |
| retry-after | `RetryInfo.retry_delay` |
| correlation id | `ErrorInfo.metadata[correlation_id]` |

- `409` is `ABORTED`, not `ALREADY_EXISTS`: a conflict is as often an optimistic-lock failure; the problem code says
  which.
- `GrpcStatusCodes.forProblemStatus` is an exhaustive `switch` with no default: a new status in web-core stops
  compilation until it gets a gRPC code.
- **Never on the wire**: exception messages, class names, stack traces, free-form problem properties (including
  `debug`), rejected values unless HTTP would carry them. The encoder takes no `Throwable`.
- Decoding never throws; a non-platform status decodes to an approximation with `fromPlatform() == false`.

### Operation message

`ludwig/operation/v1/operation.proto` is web-core's `OperationResponse` on the wire:

```proto
import "ludwig/operation/v1/operation.proto";

service ExportService {
  rpc StartExport(StartExportRequest) returns (ludwig.operation.v1.Operation) {
    option idempotency_level = IDEMPOTENT;
  }
}
```

```java
return OperationMessages.toMessage(run.toOperationResponse(), Duration.ofSeconds(5));
```

`retry_after` carries what HTTP puts in `Retry-After`. A contract never declares its own status enum; the enum in
this proto is the single exemption from the operation-vocabulary architecture rule. Both conversions are exhaustive
switches; the proto3 zero value and `UNRECOGNIZED` are rejected, not read as `PENDING`.

### Context bridge

Identity, correlation and preferences live in thread-locals, but gRPC delivers each callback on whichever executor
thread is free. `ContextRestoringServerInterceptor` attaches a gRPC `Context` and applies every `ThreadBinding`
**around each callback**, undoing them in reverse after. A binding restores the thread as it found it (pooled
threads may carry an outer scope). The server starter contributes the bindings.

## Configuration

None: the module has no auto-configuration; the starters construct what they need.

## Deployment

Nothing to deploy.

## Observability

Not applicable at this level; calls are instrumented by the starters. The correlation id travels as
`x-correlation-id` and inside every error status.

## Audit

Not applicable.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| Gate fails in `check_protos.sh` | Contract violates a rule above | Fix the proto as the message says |
| Gate fails in `manifest.sh layout` for a `-grpc-api` module | Missing registration or wrong parent | Add the `<module>`, BOM entry and jacoco-aggregate dependency |
| Caller receives `UNKNOWN` with no details | Server is not on this platform | Expected; `fromPlatform()` is false |
| Identity or correlation missing in a gRPC handler | Handler reads thread-locals outside the bridged callbacks (own executor) | Capture values on the callback thread and pass them on |

## Testing

`ContextBridgeTest` delivers callbacks on threads other than the interceptor's and interleaves two callers on one
pooled thread; codec round-trip tests per status; exhaustive-switch tests; `ModuleIndependenceTest` fails on any
transport, servlet or platform type other than web-core.

## Design decisions

- **No transport, no Spring wiring, no validation** here: only what both sides must agree on.
- **One problem pipeline for HTTP and gRPC**: mappers written for HTTP apply to gRPC.
- **Exhaustive switches without defaults** for every vocabulary crossing, so drift is a compile error.
