package ru.ludwigandreas.testsupport.container;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/**
 * An S3 client onto the shared LocalStack, and the two bucket chores every S3 test repeats.
 *
 * <h2>Why the tests get their own client rather than the module's</h2>
 *
 * <p>A test that writes its fixture with the same {@code ObjectStore} it is testing proves less: a
 * bug in the write path hides itself by making the read path agree. This client is built directly from
 * the AWS SDK so the fixture and the code under test have no implementation in common.
 *
 * <p>It is nonetheless configured the way the autoconfiguration configures its own - path-style
 * access and the Apache HTTP client, named explicitly rather than left to the SDK's classpath scan -
 * so a future SDK changing its default client is a compile-time decision here rather than a runtime
 * surprise.
 *
 * <h2>Why the bucket helpers are idempotent</h2>
 *
 * <p>The container is shared across the whole JVM, so after the first test the bucket already exists.
 * {@link #ensureBucket(String)} therefore swallows exactly the two "it is already there" errors and
 * nothing else - catching more would hide a genuine misconfiguration.
 */
public final class LocalStackS3 {

    private static volatile S3Client client;

    private LocalStackS3() {
    }

    /**
     * The shared client, built on first use.
     *
     * @return a client pointed at the shared LocalStack
     */
    public static S3Client client() {
        S3Client existing = client;
        if (existing == null) {
            synchronized (LocalStackS3.class) {
                existing = client;
                if (existing == null) {
                    existing = build();
                    client = existing;
                }
            }
        }
        return existing;
    }

    /**
     * Creates a bucket unless it is already there.
     *
     * @param bucket the bucket name
     */
    public static void ensureBucket(String bucket) {
        try {
            client().createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
            // The container is shared across the suite, so after the first test the bucket is there.
        }
    }

    /**
     * Deletes every object in a bucket, leaving the bucket itself.
     *
     * <p>The S3 counterpart of {@link ru.ludwigandreas.testsupport.data.DatabaseCleaner}: a shared
     * container means a test cannot assume an empty bucket by construction, so emptying it is the
     * price of not starting a container per class.
     *
     * @param bucket the bucket name
     */
    public static void clearBucket(String bucket) {
        ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket).build();
        client().listObjectsV2(request).contents().forEach(object ->
                client().deleteObject(DeleteObjectRequest.builder()
                        .bucket(bucket).key(object.key()).build()));
    }

    private static S3Client build() {
        var localStack = Containers.localStack();
        return S3Client.builder()
                .endpointOverride(URI.create(localStack.getEndpoint().toString()))
                .region(Region.of(localStack.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        localStack.getAccessKey(), localStack.getSecretKey())))
                .forcePathStyle(true)
                .httpClientBuilder(ApacheHttpClient.builder())
                .build();
    }
}
