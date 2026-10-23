# object-storage-spring-boot-starter

***English** · [Русский](README.ru.md)*

The platform's bucket client: one `ObjectStore` interface whose central method is a **ranged read**, lazy
paged listing, one parser for `s3://bucket/key` and `file:///path`, an AWS SDK v2 implementation (AWS, MinIO,
Ceph RGW, LocalStack) and a filesystem implementation with real ranged reads - both held to one contract test.
No internal retries; credentials only from the SDK's provider chain.

| | |
|---|---|
| Role | starter |
| Depends on (in-repo) | [`web-core-spring-boot-starter`](../web-core-spring-boot-starter) |
| Used by | `export`, `file-action`, `file-ingest` starters |
| Surfaces | outbound S3 / filesystem |

| Piece | What it is |
|---|---|
| `ObjectStore` | head, open, **ranged** open, lazily paged list, put, copy, delete, exists |
| `ObjectUri` | The one parser for `s3://bucket/key` and `file:///path`; no method takes bucket and key separately |
| `ByteRange` | Inclusive-end HTTP window, converted from exclusive-end Java lengths in one place |
| `StoredObject` / `ObjectSummary` / `PutOptions` | What a head, a listing entry and a put know |
| `S3ObjectStore` | AWS SDK v2 |
| `FilesystemObjectStore` | A directory, with real ranged reads |
| `ObjectStoreException` family | Four outcomes, rendered by web-core |

## Usage

```xml
<dependency>
    <groupId>ru.ludwigandreas</groupId>
    <artifactId>object-storage-spring-boot-starter</artifactId>
</dependency>
```

The AWS SDK is optional; a service using the S3 store adds it (versions from `ludwig-bom`):

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

### Ranged read

`open(uri, ByteRange)` lets an interrupted 1 GB read resume at byte 800 M without re-downloading 800 MB -
which is what makes file-ingest checkpoints worth having. S3 sends a `Range` header; the filesystem store
positions a `SeekableByteChannel`. **A range starting past the end returns an empty stream** on both (S3's 416
included): that is exactly what a run resumed after consuming the whole object asks for.

### Lazy listing

```java
try (Stream<ObjectSummary> drops = store.list("s3://partner-drop/catalogue/")) {
    drops.filter(o -> o.name().endsWith(".csv.gz"))
         .findFirst()
         .ifPresent(this::ingest);
}
```

Pages are fetched on demand via S3's continuation token. **The stream holds a connection between pages and must
be closed** (try-with-resources).

### Filesystem store

For laptops without a bucket, single-node deployments, and the module's own contract tests. Both schemes resolve
under the configured root:

- `file:///<root>/some/name` - must already lie under the root;
- `s3://bucket/key` → `<root>/bucket/key` - a job configured for `s3://partner-drop/...` runs unchanged against a
  local directory.

A file written on instance A is only on instance A.

**Its etag is size + modification time (ns), not a content hash.** A corrected re-upload gets a new etag; a
rewrite that preserves the modification time (`cp -p`, archive restore) does **not**, and would be skipped as a
duplicate by file-ingest. Use S3 where exactly-once must survive such restores.

### Errors

`LocalizedException`s rendered by web-core (no advice of its own); text in
`i18n/ludwig-storage-messages[_ru].properties`.

| Code | Status | When |
|---|---|---|
| `ludwig.storage.error.object-not-found` | 404 | Nothing stored there |
| `ludwig.storage.error.invalid-uri` | 400 | Not `s3://…` / `file:///…`, or contains `..` |
| `ludwig.storage.error.access-denied` | 502 | The store refused **this service's** principal; not transient |
| `ludwig.storage.error.store-unavailable` | 503 | Everything else |

502, not 403, for access denied: a 403 would tell the *caller* their credentials were insufficient.

## Configuration

```yaml
ludwig:
  storage:
    enabled: true
    type: s3                       # or filesystem (default)
    s3:
      region: us-east-1            # required even for non-AWS stores (SigV4 signs it)
      endpoint: http://minio:9000  # unset for AWS
      path-style-access: true      # required for almost every self-hosted store
      max-attempts: 2              # SDK attempts including the first
      connect-timeout: 5s
      socket-timeout: 60s          # gap between bytes, not transfer length
      connection-acquire-timeout: 10s
      max-connections: 50
      list-page-size: 0            # 0 = store default (1000 on S3)
      credentials:
        source: default            # or 'static' - local MinIO/LocalStack only
    filesystem:
      root: /var/lib/ludwig/objects
```

| Property | Default | Meaning |
|---|---|---|
| `ludwig.storage.enabled` | `true` | Master switch |
| `ludwig.storage.type` | `filesystem` | `s3` or `filesystem` |
| `ludwig.storage.s3.region` | `us-east-1` | Signed by SigV4, so set it even for non-AWS stores |
| `ludwig.storage.s3.endpoint` | unset | Non-AWS endpoint |
| `ludwig.storage.s3.path-style-access` | `false` | `true` for self-hosted stores; wrong value shows as a DNS error (`bucket.10.0.0.5`) |
| `ludwig.storage.s3.max-attempts` | `2` | SDK retries kept at one: region redirects and clock-skew re-signing need it; callers own the backoff budget |
| `ludwig.storage.s3.connect-timeout` / `socket-timeout` / `connection-acquire-timeout` | `5s` / `60s` / `10s` | |
| `ludwig.storage.s3.max-connections` | `50` | Pool size |
| `ludwig.storage.s3.list-page-size` | `0` | Page size of listings |
| `ludwig.storage.s3.credentials.source` | `default` | `default` = SDK provider chain; `static` (with `access-key` / `secret-key`) logs a warning |
| `ludwig.storage.filesystem.root` | a temp directory | Must already exist when configured (never created) |

## Deployment

- **Credentials** come from the SDK's `DefaultCredentialsProvider` chain: environment variables, Kubernetes web
  identity token (IRSA and equivalents), profile file, instance metadata. Nothing in YAML or POMs.
  `credentials.source: static` is for local MinIO/LocalStack only.
- **Bucket policy**: the service principal needs the operations its modules use (get/head/list for ingest;
  put/delete for export; copy for archival).
- **Filesystem store**: `root` must exist, be on a persistent volume and be backed up; it is never created.
- **Dependencies**: `netty-nio-client` and `apache5-client` are excluded from the SDK (`apache5-client` needs
  httpclient5 5.5 and fails with `NoClassDefFoundError` against the 5.3.x Spring Boot manages). `apache-client`
  provides pooling and a separate connection-acquire timeout.

## Observability

**Logs.** `INFO` with the temp root when no filesystem root is configured; `WARN` when static credentials are in
use.

**Metrics.** None of its own. AWS SDK metrics are not published.

**Known gaps:** no metrics or traces for store operations (latency, errors by outcome, bytes transferred) and no
health indicator for bucket reachability. A slow or failing bucket is visible only through the calling module's
metrics (export, file-ingest) and the 502/503 problems it raises. A Micrometer `ExecutionInterceptor` on the S3
client, tagged by operation and outcome, would close this.

## Audit

Not applicable: storage operations are audited by the calling module (export runs, ingest runs, file actions),
which knows who acted and why.

## Runbook

| Symptom | Likely cause | What to do |
|---|---|---|
| `ludwig.storage.error.access-denied` (502) | Bucket policy / IAM role does not allow the operation for this service's principal | Fix the policy; not transient - retrying will not help |
| `store-unavailable` (503) | Endpoint unreachable, timeouts, throttling | Check network to the endpoint, store health; `socket-timeout` for slow transfers |
| `UnknownHostException` for `bucket.<host>` | `path-style-access=false` against a self-hosted store | Set `path-style-access: true` |
| `NoClassDefFoundError` mentioning `TlsSocketStrategy` | `apache5-client` came back on the classpath | Exclude `software.amazon.awssdk:apache5-client` |
| Startup fails: filesystem root does not exist | Typo or volume not mounted | Fix the path or the mount |
| Ingest skipped a corrected file (filesystem store) | Timestamp-preserving rewrite kept the etag | Touch the file, or use S3 |
| Connection pool exhausted | Unclosed `list` streams or too many concurrent transfers | Close streams; raise `max-connections` |

## Testing

- `mvn test`: `ObjectUriTest` (parsing and refusals), `ByteRangeTest` (inclusive/exclusive arithmetic).
- `mvn verify` (Docker): `ObjectStoreContractTest` runs twice - `FilesystemObjectStoreIT` and `S3ObjectStoreIT`
  (LocalStack). Covers round-trip, head metadata, ranged reads asserted **byte for byte**, ranged read past EOF,
  listing across a continuation-token boundary (small `list-page-size`), copy, delete of an absent object (twice),
  open of a missing object, and a same-name same-length re-upload yielding a new etag.
- **LocalStack, not MinIO**, because MinIO images cannot be pulled anonymously and so cannot be digest-pinned. The
  pin lives only in `test-support`'s `images.properties` (`LudwigTestImages.LOCALSTACK`).

## Design decisions

- **One client for the platform**, so "is deleting an absent object a success", "does a listing fit in memory"
  and "who retries" have one answer. Deleting an absent object succeeds.
- **No retries in the store**: the caller has a backoff budget; nested budgets multiply.
- **`ObjectUri` everywhere**: one place for scheme parsing and path-traversal refusal.
- **Synchronous client only**: no Netty thread pool in every service that stores a file.
