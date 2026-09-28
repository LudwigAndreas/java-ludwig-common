package ru.ludwigandreas.storage.integration;

import java.net.URI;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.localstack.LocalStackContainer;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.s3.S3ObjectStore;
import ru.ludwigandreas.testsupport.container.Containers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * The shared contract, run against a real S3 API in a container.
 *
 * <h2>Why LocalStack and not MinIO</h2>
 *
 * <p>Recorded on {@link ru.ludwigandreas.testsupport.image.LudwigTestImages#LOCALSTACK}, which is
 * where the pin now lives: MinIO's images can no longer be pulled anonymously, so there is no digest
 * to pin, and an unpinned MinIO would be a worse outcome than a different, pinnable S3
 * implementation. LocalStack's S3 implements everything this contract exercises - ranged GETs,
 * {@code 416} past the end, continuation-token paging, {@code CopyObject} and content-derived etags.
 * An estate that would rather test against MinIO changes one line in {@code images.properties}.
 *
 * <h2>The credentials here are not a counterexample to the credentials rule</h2>
 *
 * <p>{@code test}/{@code test} are LocalStack's published constants for an ephemeral container on
 * this machine. They are static credentials in the one situation
 * {@code ObjectStorageProperties.Credentials} says static credentials are for.
 */
class S3ObjectStoreIT extends ObjectStoreContractTest {

    private static final String BUCKET = "contract-bucket";

    /** Small enough that the contract's twelve objects cross several continuation tokens. */
    private static final int LIST_PAGE_SIZE = 5;

    /**
     * The shared LocalStack, one per JVM rather than one per class.
     *
     * <p>The contract writes to a distinct key per case, so sharing the bucket costs nothing and saves
     * a container start per assertion - and now saves one for the whole reactor rather than per suite.
     */
    private static final LocalStackContainer LOCALSTACK = Containers.localStack();

    private static ObjectStore store;

    @BeforeAll
    static void createClientAndBucket() {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
                .region(Region.of(LOCALSTACK.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                // Path style, because the container is reached by host and port and
                // `bucket.localhost` does not resolve. The same switch a self-hosted store needs.
                .forcePathStyle(true)
                // The same HTTP client the autoconfiguration wires. Named explicitly rather than
                // left to the SDK's classpath scan so that this test exercises what ships, and so
                // that a future SDK changing its default client is a compile-time decision here
                // rather than a runtime surprise in a deployment.
                .httpClientBuilder(ApacheHttpClient.builder())
                // The same minimal policy the autoconfiguration applies, so the test exercises the
                // retry behaviour the module actually ships rather than the SDK's default.
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryPolicy(RetryPolicy.builder().numRetries(1).build())
                        .build())
                .build();
        client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        store = new S3ObjectStore(client, LIST_PAGE_SIZE);
    }

    @Override
    protected ObjectStore store() {
        return store;
    }

    @Override
    protected String uri(String key) {
        return "s3://" + BUCKET + "/" + key;
    }

    @Override
    protected int listPageSize() {
        return LIST_PAGE_SIZE;
    }
}
