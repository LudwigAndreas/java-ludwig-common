package ru.ludwigandreas.storage.config;

import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.s3.S3ObjectStore;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * The S3 half of the module, in its own auto-configuration for a reason that is not organisational.
 *
 * <h2>Why this is a separate class</h2>
 *
 * <p>The AWS SDK is an optional dependency, so this module has to load cleanly on a context that does
 * not have it - a service using only the filesystem store, or one that resolved this module
 * transitively for the {@code ObjectStore} interface alone. A {@code @Bean} method whose signature
 * names {@code S3Client} makes that impossible if it sits in a class Spring introspects: the
 * container calls {@code getDeclaredMethods} on every configuration class it processes, and that
 * throws a {@code NoClassDefFoundError} on a missing parameter type <em>before</em> any
 * {@code @ConditionalOnClass} on the method is evaluated. The condition guards whether the bean is
 * created, not whether the class is read.
 *
 * <p>A class-level {@code @ConditionalOnClass} is different: Spring Boot evaluates it from the class
 * file's ASM metadata without loading the class at all. That is the only arrangement that makes an
 * optional dependency genuinely optional, and it is why splitting this out is a correctness fix
 * rather than tidying.
 *
 * <h2>Ordering</h2>
 *
 * <p>Before {@code ObjectStorageAutoConfiguration}, so that when {@code type} is {@code s3} this
 * class's {@code ObjectStore} is already defined and the filesystem bean's
 * {@code @ConditionalOnMissingBean} sees it. Both are additionally gated on the property, so the
 * ordering is a belt to the property's braces rather than the only thing keeping two stores off the
 * context.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(S3Client.class)
@AutoConfigureBefore(ObjectStorageAutoConfiguration.class)
@EnableConfigurationProperties(ObjectStorageProperties.class)
@ConditionalOnProperty(prefix = "ludwig.storage", name = "enabled", matchIfMissing = true)
public class S3ObjectStoreAutoConfiguration {

    /**
     * The S3 client, built from the properties and from credentials that are not in them.
     *
     * @param properties the module's configuration
     * @return a configured, thread-safe client
     */
    @Bean
    @ConditionalOnMissingBean(S3Client.class)
    @ConditionalOnProperty(prefix = "ludwig.storage", name = "type", havingValue = "s3")
    public S3Client ludwigS3Client(ObjectStorageProperties properties) {
        ObjectStorageProperties.S3 s3 = properties.getS3();
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(credentialsProvider(s3))
                // Named explicitly rather than left to the SDK's classpath scan. The SDK's default
                // sync client has already changed once - see this module's POM, where apache5-client
                // is excluded because it needs an httpclient5 newer than the platform's Boot line
                // manages - and a default that changes underneath a release is not something to be
                // discovered by a NoClassDefFoundError on the first request.
                .httpClientBuilder(ApacheHttpClient.builder()
                        .connectionTimeout(s3.getConnectTimeout())
                        .socketTimeout(s3.getSocketTimeout())
                        .connectionAcquisitionTimeout(s3.getConnectionAcquireTimeout())
                        .maxConnections(s3.getMaxConnections()))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        // The one line that makes ObjectStore's "implementations do not retry" true
                        // rather than aspirational. Left at the SDK's default, every caller would get
                        // three attempts with exponential backoff underneath its own budget, and the
                        // total wait before a failure surfaced would be a number nobody had chosen.
                        // See S3ObjectStore for why this is not zero.
                        .retryPolicy(RetryPolicy.builder()
                                .numRetries(Math.max(0, s3.getMaxAttempts() - 1))
                                .build())
                        .build());
        if (s3.getEndpoint() != null && !s3.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(s3.getEndpoint()));
        }
        if (s3.isPathStyleAccess()) {
            builder.forcePathStyle(true);
        }
        return builder.build();
    }

    /**
     * The S3 store.
     *
     * @param client     the configured client
     * @param properties the module's configuration
     * @return the store
     */
    @Bean
    @ConditionalOnMissingBean(ObjectStore.class)
    @ConditionalOnProperty(prefix = "ludwig.storage", name = "type", havingValue = "s3")
    public ObjectStore s3ObjectStore(S3Client client, ObjectStorageProperties properties) {
        return new S3ObjectStore(client, properties.getS3().getListPageSize());
    }

    private AwsCredentialsProvider credentialsProvider(ObjectStorageProperties.S3 s3) {
        ObjectStorageProperties.Credentials credentials = s3.getCredentials();
        if (credentials.getSource() == ObjectStorageProperties.CredentialsSource.STATIC) {
            if (credentials.getAccessKey() == null || credentials.getSecretKey() == null) {
                throw new IllegalStateException("ludwig.storage.s3.credentials.source is 'static' but"
                        + " access-key or secret-key is missing");
            }
            log.warn("Object storage is using statically configured credentials. This is intended for a"
                    + " local MinIO or LocalStack only; deployed services should leave"
                    + " ludwig.storage.s3.credentials.source at 'default'.");
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(credentials.getAccessKey(), credentials.getSecretKey()));
        }
        // The chain: environment, web identity token (IRSA and its equivalents), shared profile,
        // instance metadata. No credential reaches this process through a POM or a YAML file, which is
        // the same rule jib and `deploy` follow for the registry and the artifact repository.
        return DefaultCredentialsProvider.create();
    }
}
