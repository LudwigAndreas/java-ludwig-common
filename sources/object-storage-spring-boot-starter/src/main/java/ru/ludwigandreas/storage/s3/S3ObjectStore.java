package ru.ludwigandreas.storage.s3;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.storage.api.ByteRange;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.ObjectSummary;
import ru.ludwigandreas.storage.api.ObjectUri;
import ru.ludwigandreas.storage.api.PutOptions;
import ru.ludwigandreas.storage.api.StoredObject;
import ru.ludwigandreas.storage.exception.InvalidObjectUriException;
import ru.ludwigandreas.storage.exception.ObjectNotFoundException;
import ru.ludwigandreas.storage.exception.ObjectStoreAccessDeniedException;
import ru.ludwigandreas.storage.exception.ObjectStoreException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The {@link ObjectStore} implementation everybody actually deploys.
 *
 * <p>Speaks the S3 API, which means AWS S3 and also MinIO, Ceph RGW, LocalStack and the several
 * other things that implement it. Nothing here depends on an AWS-only feature; the endpoint,
 * region and path-style switch that make the non-AWS ones work live in
 * {@code ObjectStorageProperties}.
 *
 * <h2>This class does not retry, and making that true took work</h2>
 *
 * <p>The contract says implementations do not retry, because the caller has its own backoff budget
 * and two budgets multiply rather than add. Stating that is not enough: the AWS SDK retries by
 * default, three times with exponential backoff and jitter, and a store that merely refrained from
 * writing its own loop would still have handed every caller a second budget it never asked for and
 * cannot see.
 *
 * <p>So the SDK's policy is configured explicitly, in {@code ObjectStorageAutoConfiguration}, down to
 * a small number of attempts - by default two, meaning one retry. Not zero, and the difference
 * matters: the SDK's retry layer is also what transparently follows a bucket's region redirect
 * (a {@code 301} carrying {@code x-amz-bucket-region}) and what re-signs a request whose clock skew
 * was rejected. Zero attempts would turn both of those into hard failures that no amount of
 * caller-side retrying could fix, because the caller would repeat the same unsigned-for-the-region
 * request. One retry covers the mechanical cases and leaves the judgement about a failing bucket
 * where it belongs, with the caller that knows whether this was attempt one or attempt three of its
 * own run.
 *
 * <h2>Streams hold connections</h2>
 *
 * <p>Every {@code open} returns the SDK's response stream, which owns a pooled HTTP connection until
 * it is closed or fully consumed. A caller that abandons one per file exhausts the pool, and the
 * symptom is a hang in {@code connection acquire} rather than an error naming the stream that was
 * dropped - which is why the interface says, twice, that the caller closes it.
 *
 * <h2>Listing pages, and never materialises</h2>
 *
 * <p>{@link #list(String)} returns a stream over an iterator that fetches the next page only when
 * the previous one is exhausted, driven by S3's continuation token. The SDK's own
 * {@code listObjectsV2Paginator} would do the same thing; it is not used because it returns the
 * SDK's iterable type, which would put an SDK type in the signature of a method on a
 * platform-neutral interface and would have to be adapted here anyway.
 */
@Slf4j
public class S3ObjectStore implements ObjectStore {

    /** S3's answer to a range that starts at or past the end of the object. */
    private static final int RANGE_NOT_SATISFIABLE = 416;

    private static final int NOT_FOUND = 404;
    private static final int FORBIDDEN = 403;

    private final S3Client client;
    private final int listPageSize;

    /**
     * Creates the store over an already-configured client, letting the server choose its page size.
     *
     * @param client the configured client; its retry policy is the caller's responsibility and is set
     *               by this module's autoconfiguration - see the note on this class
     */
    public S3ObjectStore(S3Client client) {
        this(client, 0);
    }

    /**
     * Creates the store with an explicit listing page size.
     *
     * <p>The page size exists for two reasons that point the same way. Operationally, a smaller page
     * bounds how much of a listing is in flight at once for a consumer that only wants the first few
     * entries of a prefix holding a year of files. More importantly, it is the only way this module's
     * own tests can cross a continuation-token boundary without writing more than a thousand objects
     * into a container: S3's default page is 1000 keys, so a pagination test against the default
     * would either be slow enough that nobody runs it or, far more likely, would quietly never
     * paginate and pass while asserting nothing.
     *
     * @param client       the configured client
     * @param listPageSize keys per listing request, or zero or less to let the server decide
     */
    public S3ObjectStore(S3Client client, int listPageSize) {
        this.client = client;
        this.listPageSize = listPageSize;
    }

    @Override
    public StoredObject head(String uri) {
        ObjectUri parsed = requireS3(uri);
        try {
            HeadObjectResponse response = client.headObject(HeadObjectRequest.builder()
                    .bucket(parsed.container())
                    .key(parsed.key())
                    .build());
            return new StoredObject(parsed.value(), response.contentLength(), response.eTag(),
                    response.versionId(), response.lastModified(), response.contentType());
        } catch (SdkException e) {
            throw translate("head", parsed, e);
        }
    }

    @Override
    public InputStream open(String uri) {
        ObjectUri parsed = requireS3(uri);
        try {
            return client.getObject(GetObjectRequest.builder()
                    .bucket(parsed.container())
                    .key(parsed.key())
                    .build());
        } catch (SdkException e) {
            throw translate("open", parsed, e);
        }
    }

    @Override
    public InputStream open(String uri, ByteRange range) {
        ObjectUri parsed = requireS3(uri);
        try {
            return client.getObject(GetObjectRequest.builder()
                    .bucket(parsed.container())
                    .key(parsed.key())
                    // ByteRange renders the header, so the inclusive-end convention is applied in
                    // exactly one place in this module rather than once per implementation.
                    .range(range.toHeaderValue())
                    .build());
        } catch (AwsServiceException e) {
            if (e.statusCode() == RANGE_NOT_SATISFIABLE) {
                // Promised by ObjectStore#open(String, ByteRange): a range starting at or past EOF is
                // an empty read, not a failure. It is the range a run resumed after having already
                // consumed the whole object asks for - which is precisely what a crash between the
                // final batch and the run being marked complete produces - so turning it into an
                // exception would fail the one restart that had nothing left to do.
                log.debug("Range {} is past the end of {}; returning an empty stream",
                        range.toHeaderValue(), parsed.value());
                return InputStream.nullInputStream();
            }
            throw translate("open", parsed, e);
        } catch (SdkException e) {
            throw translate("open", parsed, e);
        }
    }

    @Override
    public Stream<ObjectSummary> list(String prefix) {
        ObjectUri parsed = requireS3(prefix);
        Iterator<ObjectSummary> iterator = new PagingIterator(client, parsed, listPageSize);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL),
                false);
    }

    @Override
    public StoredObject put(String uri, Path file, PutOptions options) {
        ObjectUri parsed = requireS3(uri);
        try {
            PutObjectRequest.Builder request = PutObjectRequest.builder()
                    .bucket(parsed.container())
                    .key(parsed.key())
                    .metadata(options.userMetadata());
            if (options.contentType() != null) {
                request.contentType(options.contentType());
            }
            // RequestBody.fromFile streams the file and takes the content length from the file system,
            // so a 1 GB upload never sits in this process's heap. RequestBody.fromInputStream without
            // a length would buffer, which is the failure this module exists to make impossible.
            PutObjectResponse response = client.putObject(request.build(), RequestBody.fromFile(file));
            log.debug("Stored {} at {}", file, parsed.value());
            return new StoredObject(parsed.value(), sizeOf(file), response.eTag(),
                    response.versionId(), Instant.now(), options.contentType());
        } catch (SdkException e) {
            throw translate("put", parsed, e);
        }
    }

    @Override
    public StoredObject copy(String sourceUri, String targetUri) {
        ObjectUri source = requireS3(sourceUri);
        ObjectUri target = requireS3(targetUri);
        try {
            CopyObjectResponse response = client.copyObject(CopyObjectRequest.builder()
                    .sourceBucket(source.container())
                    .sourceKey(source.key())
                    .destinationBucket(target.container())
                    .destinationKey(target.key())
                    .build());
            return new StoredObject(target.value(), sizeOfRemote(target),
                    response.copyObjectResult() == null ? "" : response.copyObjectResult().eTag(),
                    response.versionId(),
                    response.copyObjectResult() == null
                            ? Instant.now() : response.copyObjectResult().lastModified(),
                    null);
        } catch (SdkException e) {
            throw translate("copy", source, e);
        }
    }

    @Override
    public void delete(String uri) {
        ObjectUri parsed = requireS3(uri);
        try {
            // S3's DeleteObject is already idempotent: deleting a key that is not there returns 204.
            // That is the behaviour ObjectStore#delete requires, so there is nothing to add here -
            // and nothing to take away either, which is worth saying because an exists() guard in
            // front of this would introduce a race and make the operation less correct, not more.
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(parsed.container())
                    .key(parsed.key())
                    .build());
        } catch (SdkException e) {
            throw translate("delete", parsed, e);
        }
    }

    @Override
    public boolean exists(String uri) {
        ObjectUri parsed = requireS3(uri);
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(parsed.container())
                    .key(parsed.key())
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (AwsServiceException e) {
            if (e.statusCode() == NOT_FOUND) {
                // HeadObject has no response body, so the SDK cannot always map a 404 onto
                // NoSuchKeyException - a bucket the credentials cannot list answers 404 for a missing
                // key rather than 403. Both mean "not there" for this method's purposes.
                return false;
            }
            throw translate("exists", parsed, e);
        } catch (SdkException e) {
            throw translate("exists", parsed, e);
        }
    }

    private ObjectUri requireS3(String uri) {
        ObjectUri parsed = ObjectUri.parse(uri);
        if (!parsed.isS3()) {
            // Routing a file: location to the S3 store is a wiring mistake, and the useful thing to
            // do about it is to say which location was misrouted rather than to let the SDK reject a
            // bucket named after a directory.
            throw new InvalidObjectUriException(parsed.value());
        }
        return parsed;
    }

    private long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new ObjectStoreException("put", ObjectUri.ofFile(file).value(), e);
        }
    }

    private long sizeOfRemote(ObjectUri target) {
        // CopyObject does not report the size of what it produced. One HeadObject afterwards is the
        // only way to fill the field honestly; returning 0 or the source's size would put a number in
        // a record that callers are entitled to believe.
        return head(target.value()).size();
    }

    /**
     * Maps an SDK failure onto this module's small exception family.
     *
     * <p>The three outcomes are distinguished because the responses to them differ: a missing object
     * is often expected and handled, a refused one will be refused identically forever and must not
     * consume a retry budget, and everything else is a store that may come back.
     */
    private ObjectStoreException translate(String operation, ObjectUri uri, SdkException cause) {
        if (cause instanceof NoSuchKeyException || cause instanceof NoSuchBucketException) {
            return new ObjectNotFoundException(uri.value(), cause);
        }
        if (cause instanceof AwsServiceException service) {
            int status = service.statusCode();
            if (status == NOT_FOUND) {
                return new ObjectNotFoundException(uri.value(), cause);
            }
            if (status == FORBIDDEN) {
                return new ObjectStoreAccessDeniedException(operation, uri.value(), cause);
            }
        }
        return new ObjectStoreException(operation, uri.value(), cause);
    }

    /**
     * Walks S3's pages, fetching the next one only when the previous is exhausted.
     *
     * <p>An iterator rather than a recursive stream concatenation because the continuation token is
     * state, and state in a stream pipeline is the thing that makes a stream unsafe to split. This
     * one is explicitly sequential, which is also why {@link #list(String)} builds its stream with
     * {@code parallel = false}.
     */
    private static final class PagingIterator implements Iterator<ObjectSummary> {

        private final S3Client client;
        private final ObjectUri prefix;
        private final int pageSize;
        private Iterator<S3Object> page = Collections.emptyIterator();
        private String continuationToken;
        private boolean exhausted;

        private PagingIterator(S3Client client, ObjectUri prefix, int pageSize) {
            this.client = client;
            this.prefix = prefix;
            this.pageSize = pageSize;
        }

        @Override
        public boolean hasNext() {
            while (!page.hasNext() && !exhausted) {
                fetchNextPage();
            }
            return page.hasNext();
        }

        @Override
        public ObjectSummary next() {
            if (!hasNext()) {
                throw new NoSuchElementException(
                        "No more objects under " + prefix.value());
            }
            S3Object object = page.next();
            return new ObjectSummary(ObjectUri.ofS3(prefix.container(), object.key()).value(),
                    object.size(), object.eTag(), object.lastModified());
        }

        private void fetchNextPage() {
            try {
                ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                        .bucket(prefix.container())
                        .prefix(prefix.key())
                        .continuationToken(continuationToken);
                if (pageSize > 0) {
                    request.maxKeys(pageSize);
                }
                ListObjectsV2Response response = client.listObjectsV2(request.build());
                page = response.contents().iterator();
                continuationToken = response.nextContinuationToken();
                // Trust isTruncated over the token's nullness: a store that returns an empty page
                // with a token would otherwise loop here forever, and at least one S3-compatible
                // implementation has done exactly that.
                exhausted = !Boolean.TRUE.equals(response.isTruncated()) || continuationToken == null;
            } catch (SdkException e) {
                throw new ObjectStoreException("list", prefix.value(), e);
            }
        }
    }
}
