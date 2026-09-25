# object-storage-spring-boot-starter

[English] | [Русский](README.ru.md)

The platform's **one** bucket client. One `ObjectStore` interface, two implementations held to one
contract by one test suite.

It exists because two modules need a bucket for opposite reasons —
[`export-spring-boot-starter`](../export-spring-boot-starter) writes finished reports into one, and a
file ingest reads large partner drops out of one — and each of them could have grown a client of its
own. That is how `job-core`'s `JdbcRunLock` and a second, separately written distributed lock came to
exist, and the cost was never the duplicated code. It was the two subtly different answers to "what
does a dead holder look like". Two bucket clients would give two answers to *is deleting something
absent a success*, *does listing a year of daily drops fit in memory*, and *who retries* — and the
disagreement would only be discovered in the incident where it mattered.

## What is in it

| Piece | What it is |
|---|---|
| `ObjectStore` | The contract: head, open, **ranged** open, lazily paged list, put, copy, delete, exists |
| `ObjectUri` | The one place `s3://bucket/key` and `file:///path` are parsed, and the reason no method takes a bucket and a key separately |
| `ByteRange` | The inclusive-end HTTP window, converted from exclusive-end Java lengths in exactly one place |
| `StoredObject` / `ObjectSummary` / `PutOptions` | What a store knows about an object; deliberately different types, because a listing knows less than a head |
| `S3ObjectStore` | AWS SDK v2, so also MinIO, Ceph RGW and LocalStack |
| `FilesystemObjectStore` | A directory, with real ranged reads — not a stub |
| `ObjectStoreException` family | Four outcomes, rendered by `web-core`'s one RFC 9457 pipeline |

## The ranged read is what this interface is for

`open(uri, ByteRange)` is the load-bearing method, and everything else is arranged around it.

Without a ranged GET, resuming an interrupted read of a 1 GB drop at byte 800 M means re-downloading
800 MB. A checkpoint that costs 800 MB to honour is not a checkpoint: the design collapses back into
"start over", which is exactly what the checkpoint was introduced to remove. So a ranged read is not
an optional extra on top of `InputStream open(String)` — it is the reason this interface is not that.

Both implementations serve ranges natively. `S3ObjectStore` sends a `Range` header;
`FilesystemObjectStore` positions a `SeekableByteChannel`. The filesystem one could have opened the
file and skipped forward, which would be correct and would also mean that every test of resumption
passed while proving nothing, because the expensive behaviour those tests exist to catch would have
been quietly reintroduced underneath them.

### A range that starts past the end is empty, not an error

S3 answers `416 Range Not Satisfiable`; a channel positioned past EOF reads `-1`. The store makes
those one behaviour — an empty stream — and the shared contract test asserts it against both. It is
not a curiosity: it is the range a run resumed after having already consumed the whole object asks
for, which is what a crash between the final batch and the run being marked complete leaves behind.

## Listing is lazy, and that is part of the contract

`list(prefix)` returns a `Stream` that fetches the next page only when the consumer asks for it,
driven by S3's continuation token. A `List<String>` return is a module that works in every test and
falls over the first time somebody points it at a prefix holding a year of daily drops.

**The stream holds a connection between pages and must be closed.** Consume it in a
try-with-resources.

```java
try (Stream<ObjectSummary> drops = store.list("s3://partner-drop/catalogue/")) {
    drops.filter(o -> o.name().endsWith(".csv.gz"))
         .findFirst()
         .ifPresent(this::ingest);
}
```

## Implementations do not retry, and making that true took work

The contract says so for the reason `ReportSink` gives about itself: the caller already has a backoff
budget, and a second budget underneath it multiplies rather than adds. Two layers of three attempts
with exponential backoff is nine attempts and a wait nobody chose.

Stating it is not enough, because **the AWS SDK retries by default** — three times, with exponential
backoff and jitter. A store that merely refrained from writing its own loop would still have handed
every caller a second budget it never asked for and cannot see. So the SDK's policy is configured
explicitly, down to `ludwig.storage.s3.max-attempts` (default **2**, meaning one retry).

Not zero, and the difference matters: the SDK's retry layer is also what transparently follows a
bucket's region redirect and what re-signs a request rejected for clock skew. Zero attempts would turn
both into hard failures that no amount of caller-side retrying could fix, because the caller would
repeat the same wrongly-signed request.

## The filesystem store is not a stub

Three situations are real and permanent: a developer on a laptop with no bucket and no container; a
single-node deployment where an object store would be a second thing to operate for no benefit; and
this module's own tests, which run the whole contract against it so that a change to the S3 store has
something to disagree with.

Both schemes resolve under a configured root:

* `file:///<root>/some/name` — the natural form; the path must already lie under the root.
* `s3://bucket/key` → `<root>/bucket/key` — so that a job configured with
  `s3://partner-drop/catalogue/` runs unchanged against a directory in a developer's checkout. Being
  able to substitute the store without editing a single location is most of what makes it worth
  having.

What it is not is a store any instance can read any object from. A file written by instance A is on
instance A.

### Its etag is not a content hash, and that is a stated limitation

S3 hands back an etag with every listing entry for free. This store has no such thing, and the only
content-derived answer would be to read the file — which would make `list` read every byte of every
object under the prefix, a worse failure than the one lazy paging was introduced to fix.

So the etag is a digest of size and last-modified time at nanosecond resolution. It changes whenever
the file is written, which is the case that matters: a partner replacing a drop with a corrected one
gets a new etag and therefore a new run, even when the correction changed one character. What it does
**not** catch is a rewrite that preserves the modification time exactly — a `cp -p`, a restore from
an archive — which produces the same etag and would be skipped as a duplicate. An estate whose
exactly-once guarantee has to survive timestamp-preserving restores should be running against
`S3ObjectStore`, whose etag comes from the content itself.

## Credentials

**Nothing in any POM and nothing in any YAML.** The default resolves through the SDK's
`DefaultCredentialsProvider` chain: environment variables, the web identity token a Kubernetes service
account projects (IRSA and its equivalents), the shared profile file, and finally instance metadata.
Every one of those puts the secret where the deployment already manages it — the same rule jib and
`deploy` follow for the registry and the artifact repository.

`ludwig.storage.s3.credentials.source: static` exists for exactly one case: a developer running MinIO
or LocalStack locally, where the credential is a well-known constant that is not a secret at all. It
logs a warning when used, and setting it in a deployed profile is the mistake this split is shaped to
make visible in a diff.

## Errors

Four outcomes, all `LocalizedException`s rendered by `web-core`'s single `ProblemDetail` pipeline.
This module ships **no** `@RestControllerAdvice` — a second advice in a context would render some
errors one way and some another depending on which Spring ordered first.

| Code | Status | When |
|---|---|---|
| `ludwig.storage.error.object-not-found` | 404 | Nothing is stored there |
| `ludwig.storage.error.invalid-uri` | 400 | Not `s3://…` or `file:///…`, or contains a `..` segment |
| `ludwig.storage.error.access-denied` | 502 | The store refused **this service's** principal — never transient, must not consume a retry budget |
| `ludwig.storage.error.store-unavailable` | 503 | Everything else |

Access-denied renders 502 rather than 403 deliberately: a 403 from this service would tell the caller
that *their* credentials were insufficient, which is wrong and sends them to the wrong team.

Text lives in `i18n/ludwig-storage-messages[_ru].properties`. A service rewords any of it by defining
the same key in its own bundle.

## Configuration (`ludwig.storage.*`)

```yaml
ludwig:
  storage:
    enabled: true
    type: s3                       # or filesystem (the default)
    s3:
      region: us-east-1            # required even against a non-AWS store: SigV4 signs it
      endpoint: http://minio:9000  # unset for AWS itself
      path-style-access: true      # required for almost every self-hosted store
      max-attempts: 2              # attempts including the first; see above
      connect-timeout: 5s
      socket-timeout: 60s          # gap between bytes, not length of transfer
      connection-acquire-timeout: 10s
      max-connections: 50
      list-page-size: 0            # 0 = the store's own default (1000 on S3)
      credentials:
        source: default            # or 'static', for a local MinIO only
    filesystem:
      root: /var/lib/ludwig/objects
```

A configured `filesystem.root` must already exist and is never created. A typo in a path that
silently created a directory would put data somewhere nobody is watching and nobody is backing up,
and the two cases are indistinguishable afterwards. The *unconfigured* default is created, because it
is by construction a temp directory.

`path-style-access` is called out because getting it wrong produces a DNS failure rather than an S3
error: a self-hosted store is reached by IP or a bare host name, and `bucket.10.0.0.5` does not
resolve.

## Consuming it

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>object-storage-spring-boot-starter</artifactId>
</dependency>
```

Versions come from `ludwig-bom`; name none here. **The AWS SDK is an optional dependency of this
module**, so a service that wants the S3 store declares it too — still with no version:

```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>s3</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>apache-client</artifactId>
</dependency>
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>auth</artifactId>
</dependency>
```

A service using only the filesystem store, or resolving this module transitively for the interface
alone, pays for none of it.

### Two notes on the SDK's dependency graph

`netty-nio-client` is excluded: nothing here is async, and the async client would add Netty, its
transport and its own thread pool to every service that stores a file.

`apache5-client` is also excluded, and this one is not a preference. SDK 2.55 compiles it against
httpclient5 5.5 and calls `TlsSocketStrategy`, a type that does not exist in the 5.3.1
`spring-boot-dependencies` manages; left on the classpath it becomes the client the SDK picks by
default and the first request dies with a `NoClassDefFoundError` naming nothing useful. Raising the
platform's httpclient5 was the alternative and is worse — httpclient5 is what Spring's `RestClient`
and the rest-client starter use, so the whole estate's HTTP stack would move to satisfy one optional
dependency of one module. The httpclient4 branch `apache-client` uses is used by nothing else here.

`apache-client` over `url-connection-client`, in turn, because `UrlConnectionHttpClient` has no
connection pool: a listing paging through ten thousand objects would open ten thousand TCP and TLS
handshakes, and the long sequences of ranged reads this module exists for are exactly the case
pooling is for. It also cannot express a separate connection-acquire timeout, so a saturated pool
would be indistinguishable from a slow server.

## Testing

`mvn test` runs the unit suite: URI parsing and everything it refuses, and the inclusive/exclusive
range arithmetic — the one place in this module where an off-by-one produces a single duplicated or
missing record in an otherwise clean import.

`mvn verify` runs `ObjectStoreContractTest` **twice**, once as `FilesystemObjectStoreIT` and once as
`S3ObjectStoreIT` against a container. Both are named `*IT` and run at `verify`, so that "the
contract passes" is one statement rather than two — a developer running only `mvn test` would
otherwise see the filesystem half pass and reasonably conclude the contract held.

The contract covers: round-trip; head metadata; **a ranged read returning exactly the requested
bytes**, asserted on the bytes rather than the length, because an implementation reading the right
number of bytes from the wrong offset would pass a length check and corrupt every resumed ingest in a
way that looks like a parser bug; a ranged read past EOF; a listing crossing a continuation-token
boundary; copy; delete-absent-is-success, twice; open-missing raising `ObjectNotFoundException`; and
a same-name, same-length re-upload producing a new content identity.

### Why LocalStack and not MinIO

The obvious S3-compatible container is MinIO, and it is not used here for a reason that is not a
preference: **MinIO's images can no longer be pulled anonymously** from Docker Hub or quay.io, so
there is no digest this repository could pin. The platform's rule that every image is identified by
name, version *and* `sha256` digest is not negotiable — a tag can be re-pointed at different content,
which turns a reproducible test into one that passes until somebody else's release — and an unpinned
MinIO would have been a worse outcome than a different, pinnable S3 implementation.

LocalStack's S3 implements every operation the contract exercises: ranged GETs, `416` past the end,
continuation-token paging, `CopyObject`, content-derived etags. An estate with a registry it can pull
MinIO from changes two constants in `S3ObjectStoreIT` and nothing else.

The image is pinned as
`localstack/localstack:4.5.0@sha256:9d4253786e0effe974d77fe3c390358391a56090a4fff83b4600d8a64404d95d`
— the multi-architecture index digest, so the same pin resolves on an arm64 laptop and an amd64 build
agent.

The pagination test needs `list-page-size` to be smaller than the number of objects it writes. S3's
default page is 1000 keys, so a pagination test against the default would either be slow enough that
nobody runs it or — far more likely — would quietly never paginate and pass while asserting nothing.
