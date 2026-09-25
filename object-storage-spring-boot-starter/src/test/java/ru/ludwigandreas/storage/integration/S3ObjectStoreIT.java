package ru.ludwigandreas.storage.integration;

import java.net.URI;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.s3.S3ObjectStore;
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
 * <p>The obvious choice for an S3-compatible container is MinIO, and it is not used here for a
 * reason that is not a preference: MinIO's images can no longer be pulled anonymously from Docker
 * Hub or from quay.io, so there is no digest this repository could pin. The platform's rule that
 * every image is identified by name, version <em>and</em> {@code sha256} digest is not negotiable -
 * a tag can be re-pointed at different content, which turns a reproducible test into one that passes
 * until someone else's release - and an unpinned MinIO would have been a worse outcome than a
 * different, pinnable S3 implementation.
 *
 * <p>LocalStack's S3 is a full implementation of the operations this module uses, including ranged
 * GETs, {@code 416} for a range past the end, continuation-token paging, {@code CopyObject} and
 * content-derived etags, which is what the contract actually exercises. An estate that would rather
 * test against MinIO and has a registry it can pull it from changes the two constants below and
 * nothing else.
 *
 * <h2>The credentials here are not a counterexample to the credentials rule</h2>
 *
 * <p>{@code test}/{@code test} are LocalStack's published constants for an ephemeral container on
 * this machine. They are static credentials in the one situation
 * {@code ObjectStorageProperties.Credentials} says static credentials are for.
 */
@Testcontainers
class S3ObjectStoreIT extends ObjectStoreContractTest {

    /**
     * Pinned by name, version and digest. The digest is the multi-architecture index, so the same
     * pin resolves on an arm64 laptop and an amd64 build agent.
     */
    private static final DockerImageName LOCALSTACK_IMAGE = DockerImageName
            .parse("localstack/localstack:4.5.0@sha256:"
                    + "9d4253786e0effe974d77fe3c390358391a56090a4fff83b4600d8a64404d95d")
            .asCompatibleSubstituteFor("localstack/localstack");

    private static final String BUCKET = "contract-bucket";

    /** Small enough that the contract's twelve objects cross several continuation tokens. */
    private static final int LIST_PAGE_SIZE = 5;

    /**
     * Static, so one container serves the whole contract rather than being restarted per test. The
     * contract writes to a distinct key per case, so sharing the bucket costs nothing and saves a
     * container start per assertion.
     */
    @Container
    @SuppressWarnings("resource") // Testcontainers closes it; try-with-resources would stop it early.
    private static final LocalStackContainer LOCALSTACK = new LocalStackContainer(LOCALSTACK_IMAGE);

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
