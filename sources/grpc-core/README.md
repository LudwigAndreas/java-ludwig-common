# grpc-core

***English** · [Русский](README.ru.md)*

What two platform services share when they talk over gRPC, with no transport attached.

Both gRPC starters depend on this module, and a service may carry only one of them. So it holds only
what *both* sides of a call have to agree on - four things - and nothing that opens a socket.

| | |
|---|---|
| [Metadata keys](#metadata-keys) | The names under which the correlation id, idempotency key, locale and zone travel |
| [Problem codec](#a-problem-and-a-status-are-one-fact) | An RFC 9457 problem rendered as a `google.rpc.Status`, and read back |
| [Operation message](#the-operation-message) | The long-running-operation envelope, on the wire |
| [Context bridge](#the-context-bridge) | Thread-bound state restored on whichever thread gRPC delivers a callback |

It also defines [what a contract module is](#publishing-a-contract).

## Publishing a contract

A service's gRPC contract is its own module, `<service>-grpc-api`, in `sources/`, parented by
`build/ludwig-grpc-api-parent`. It holds the `.proto` files and the code generated from them and nothing
else. Callers depend on that jar; nobody keeps a private copy of the messages.

The parent supplies everything else - `grpc-protobuf`, `grpc-stub`, `protobuf-java`, this module (so a
contract can import `ludwig/operation/v1/operation.proto`), and protobuf generation, which runs in any
module with a `src/main/proto` directory. The whole POM of a contract module is:

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

plus three registrations: a `<module>` in the root `pom.xml`, an entry in `build/ludwig-bom/pom.xml`,
and a dependency of `build/jacoco-aggregate/pom.xml`. `scripts/manifest.sh layout`, which the gate runs,
fails naming the module when any of that is missing, when the parent is another one, when the module
has Java, or when it has no `.proto` - so the ten lines are checked rather than generated.

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

`scripts/check_protos.sh`, which the gate runs, fails on any departure from this shape. Each rule is
load-bearing somewhere else:

| Rule | Why it is not a matter of taste |
|---|---|
| `package ludwig.<service>.v<N>` | The version is in the package so `v2` can be served beside `v1` |
| `java_package = ru.ludwigandreas.<service>.grpc.v<N>` | It is the single coverage and analysis exclusion for generated code (`**/grpc/v*/**`); a contract outside it is measured as if somebody wrote it by hand |
| `java_multiple_files = true` | Otherwise every message is a nested class of one outer class named after the file, and renaming the file renames every type |
| every `rpc` declares `idempotency_level` | The gRPC client reads it to decide whether a failed call may be repeated. `IDEMPOTENCY_UNKNOWN` is a valid answer; silence is not, because it must not look like a decision |
| a `.proto` lives only in a `-grpc-api` module (or here) | A proto inside a service makes callers depend on a jar built as an image |

`protoc` is resolved as a Maven artifact, so the build needs no system compiler. Its version is the
`protoc.version` property in the root POM, which must equal `protobuf.version` in `ludwig-bom`; the same
script fails the gate when they differ.

**What the script cannot check:** that an `idempotency_level` is *true*, and that a change to a
published proto is wire-compatible. The first is known only to the method's author. The second needs a
baseline descriptor per release and is deferred - see `build/architecture-rules/README.md`.

## Mapping messages

A service maps between a generated message and its model with MapStruct, like at every other model
boundary - `ludwig-service-parent` puts the protobuf SPI on the annotation-processor path, and this
module supplies the configuration:

```java
@Mapper(config = ProtobufMapperConfig.class)
public interface ProductMessageMapper {
    ru.ludwigandreas.catalog.grpc.v1.Product toMessage(Product product);
}
```

`ProtobufMapperConfig` makes an absent model value leave a message field unset (a builder's setter
throws on `null`), fills repeated fields through their adders, and converts `Instant`/`Duration` to and
from `google.protobuf.Timestamp`/`Duration` (`ProtobufConversions`). The SPI does the rest: builders,
`hasX` presence (an unset optional field maps back to `null`), and `ENUM_NAME_` prefixes with the
`UNSPECIFIED` constant. `crud-service-example`'s `ProductMessageMapper` is the worked example.

## Metadata keys

`GrpcMetadataKeys` declares each key once. Every one is the HTTP header that carries the same value,
lower-cased, so there is one name per concept across both transports.

| Key | Mirrors |
|---|---|
| `authorization` | HTTP `Authorization` |
| `x-correlation-id` | `ludwig.observability.correlation.header-name` (use `forHeader` if a deployment renamed it) |
| `idempotency-key` | `IdempotencyHeaders.KEY` |
| `idempotency-replayed` | `IdempotencyHeaders.REPLAYED` |
| `accept-language` | HTTP `Accept-Language`, read by web-core's request-header preference source |
| `x-time-zone` | web-core's time-zone header |
| `retry-after` | `OperationHeaders.RETRY_AFTER` |

The owning modules are not dependencies of this one, so the literals are repeated here; the starters
that depend on both sides carry a test asserting they are equal.

## A problem and a status are one fact

Deciding *which* problem an exception is stays in `web-core`'s `ProblemMapperRegistry`, fed by the
mappers every module contributes. `GrpcStatusCodec` starts from the `ProblemDetail` that pipeline
produced and changes only the envelope. **A module never writes a gRPC-specific exception mapping**; the
mapper it contributed for HTTP is the one that takes effect.

| Problem status | gRPC code | | Problem status | gRPC code |
|---|---|---|---|---|
| 400, 405, 406, 415 | `INVALID_ARGUMENT` | | 413, 429 | `RESOURCE_EXHAUSTED` |
| 401 | `UNAUTHENTICATED` | | 500 | `INTERNAL` |
| 403 | `PERMISSION_DENIED` | | 501 | `UNIMPLEMENTED` |
| 404, 410 | `NOT_FOUND` | | 502, 503 | `UNAVAILABLE` |
| 409, 423 | `ABORTED` | | 504 | `DEADLINE_EXCEEDED` |
| 412, 422 | `FAILED_PRECONDITION` | | | |

The gRPC code is the coarse signal - what a retry policy and a circuit breaker act on. The precise
answer travels beside it, which is what lets the caller rebuild the exact problem:

| Problem member | Where it travels |
|---|---|
| status | the code above, and the exact number in `ErrorInfo.metadata[http_status]` |
| `code` | `ErrorInfo.reason` |
| type, title | `ErrorInfo.metadata[type]`, `[title]` |
| detail | the status message, and `LocalizedMessage` with its locale |
| `violations` | `BadRequest.field_violations` |
| a retry-after | `RetryInfo.retry_delay` |
| the correlation id | `ErrorInfo.metadata[correlation_id]` |

`409` is `ABORTED` rather than `ALREADY_EXISTS` because a conflict here is as often an optimistic-lock
failure as a duplicate; the problem code says which.

**The table has no default branch.** `GrpcStatusCodes.forProblemStatus` is a `switch` expression naming
every constant, so a status added to `web-core` stops this module compiling until someone decides its
gRPC code, instead of falling through to `UNKNOWN` and being discovered by a caller's circuit breaker.

**What never travels:** an exception message, a class name, a stack trace. The codec has no
`Throwable` parameter on the encoding side, so it cannot leak what it is never given. Free-form problem
properties are not forwarded either - including the `debug` member `web-core` can add to a 5xx - and a
violation's rejected value crosses the wire only if the HTTP response would have carried it.

Decoding never throws on a malformed or foreign status. A failure from a server that is not on this
platform decodes to an approximation whose `fromPlatform()` is `false`.

## The operation message

`ludwig/operation/v1/operation.proto` is `OperationResponse` on the wire. A gRPC method that starts,
polls or cancels a long-running operation returns `Operation`, built by `OperationMessages.toMessage`
from the same envelope the module would have handed to `OperationResponses` for HTTP:

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

There is still one envelope, no shared operation table, and no persistence dependency here. The
module's own run table and its richer lifecycle in `detail` are exactly as they were.

**A contract never declares a status enum of its own.** The enum in this proto is the single named
exemption from `architecture-rules`' operation-vocabulary rule, and exists only because a wire format
cannot import a Java enum. Both status switches in `OperationMessages` name every constant and have no
default, so the two vocabularies cannot drift by omission; the proto3 zero value and `UNRECOGNIZED` are
rejected rather than read as `PENDING`, because an operation whose status nobody set is not one that is
waiting.

`retry_after` carries what the HTTP rendering puts in the `Retry-After` header.

## The context bridge

The platform keeps the caller's identity, the correlation id and the caller's preferences in
thread-locals. That works for a servlet request because one thread serves it. A gRPC call has no such
thread: its events are delivered by whichever executor thread is free.

`ContextRestoringServerInterceptor` runs the rest of a call with a gRPC `Context` attached and every
`ThreadBinding` in force **around each callback**, and undoes them in reverse afterwards.

The obvious implementation - set the thread-locals once in `interceptCall` - is wrong and passes every
single-threaded test. `interceptCall` returns before the request message has arrived; for a unary call
the handler runs later, inside `onHalfClose`, on a thread the executor picks then. In production that is
routinely a different thread, where the security context is empty; and the thread `interceptCall` ran on
goes back to the pool carrying the previous caller's identity. `ContextBridgeTest` therefore delivers
callbacks on a different thread from the interceptor's, interleaving two callers on one pooled thread.

A `ThreadBinding` restores the thread *as it found it* rather than clearing it, because executor
threads are shared and an outer scope on the same thread may still need its value.

`grpc-server-spring-boot-starter` contributes the bindings; this module restores all three subsystems
while depending on none of them.

## What it deliberately does not do

- **No transport.** No Netty, no channel, no server. `ModuleIndependenceTest` fails the build if a
  transport type, a servlet type or any platform module other than `web-core` is reached from here.
- **No Spring wiring.** There is no auto-configuration; the starters construct what they need.
- **No message validation.** Validation stays in the service layer and reaches the caller through the
  problem mapping above.

## Package layout

```
ru.ludwigandreas.grpc.core
├── metadata    GrpcMetadataKeys
├── problem     GrpcStatusCodes, GrpcStatusCodec, DecodedProblem
├── operation   OperationMessages
└── context     GrpcCallContext, ThreadBinding, ContextRestoringServerInterceptor

ru.ludwigandreas.operation.grpc.v1   generated from ludwig/operation/v1/operation.proto
```
