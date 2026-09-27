# The object store and the ranged read

## Purpose
`object-storage-spring-boot-starter` exists for `open(uri, ByteRange)`. Without a ranged GET,
resuming an interrupted read of a 1 GB drop at byte 800 M means re-downloading 800 MB — and a
checkpoint that costs 800 MB to honour is not a checkpoint.

## Requirements

### Requirement: Every implementation serves ranges natively
`open(uri, ByteRange)` SHALL be served by the underlying transport's own range facility, not by
opening at zero and skipping forward.

#### Scenario: A range is read from S3
- **WHEN** `open` is called with a range
- **THEN** `S3ObjectStore` sends a `Range` header

#### Scenario: A range is read from the filesystem store
- **WHEN** `open` is called with a range
- **THEN** `FilesystemObjectStore` positions a `SeekableByteChannel`. Opening and skipping would
  be correct and would also mean every test of resumption passed while proving nothing, because
  the expensive behaviour those tests exist to catch would have been quietly reintroduced
  underneath them

### Requirement: A range starting past the end is an empty stream, not an error
Both implementations SHALL return an empty stream for a range beginning past the end of the
object.

#### Scenario: A run resumes after having already consumed the whole object
- **WHEN** a range starting at or past the object's length is requested — which is what a crash
  between the final batch and the run being marked complete leaves behind
- **THEN** an empty stream is returned. S3 would answer `416 Range Not Satisfiable` and a channel
  positioned past EOF reads `-1`; the store makes those one behaviour, and the shared contract
  test asserts it against both implementations

### Requirement: Listing is lazy and the stream must be closed
`list(prefix)` SHALL return a `Stream` that fetches the next page only when the consumer asks for
it. The stream holds a connection between pages, so callers SHALL close it.

#### Scenario: A prefix holds a year of daily drops
- **WHEN** a caller lists a large prefix and consumes only the first match
- **THEN** only the pages needed are fetched. A `List<String>` return is a module that works in
  every test and falls over the first time somebody points it at a year of data

#### Scenario: A caller lists a prefix
- **WHEN** `list` is used
- **THEN** it is consumed inside try-with-resources, because the stream holds a connection
  between pages

### Requirement: Implementations do not retry
An object store implementation SHALL NOT retry internally.

#### Scenario: A read fails transiently
- **WHEN** an underlying call fails
- **THEN** the failure surfaces to the caller, which owns the retry policy — a caller resuming
  from a checkpoint needs to decide for itself whether and from where to retry

### Requirement: The filesystem store's etag is not a content hash
`FilesystemObjectStore`'s etag SHALL be treated as an opaque change marker, and its limitation
SHALL stay stated.

#### Scenario: A caller compares etags across the two implementations
- **WHEN** a caller assumes an etag is a content hash
- **THEN** that assumption does not hold for the filesystem store, which is a stated limitation
  rather than an oversight
