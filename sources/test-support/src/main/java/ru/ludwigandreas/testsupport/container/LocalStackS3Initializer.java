package ru.ludwigandreas.testsupport.container;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.localstack.LocalStackContainer;

/**
 * Points {@code object-storage-spring-boot-starter} at the shared LocalStack.
 *
 * <h2>Why an initializer rather than {@code @ServiceConnection}</h2>
 *
 * <p>{@code @ServiceConnection} works by matching a container against a
 * {@code ConnectionDetailsFactory}, and Spring Boot 3.3 ships none for S3 - the connection-details
 * abstraction covers the services Boot itself auto-configures, and object storage is this platform's
 * own starter. So the endpoint, region and credentials have to be contributed as properties.
 *
 * <h2>Why not {@code @DynamicPropertySource}</h2>
 *
 * <p>{@code @DynamicPropertySource} is a static method on the test class, so it cannot be inherited
 * from a composed annotation - every test would have to repeat the block, which is the duplication
 * this module exists to remove. An {@code ApplicationContextInitializer} can be named by
 * {@code @ContextConfiguration}, which a meta-annotation <em>can</em> carry, so
 * {@link ru.ludwigandreas.testsupport.junit.LudwigLocalStackTest} wires this and the test writes
 * nothing.
 *
 * <p>Spring Boot 3.4's {@code DynamicPropertyRegistrar} bean would be the tidier answer; this
 * repository is on 3.3.5, where it does not exist yet. Replace this class with one when the Boot line
 * moves.
 *
 * <p>The property names are string literals rather than references to the starter's
 * {@code ObjectStorageProperties}: {@code test-support} must depend on nothing in this repository, or
 * the reactor acquires a cycle the moment an upstream module wants a container. Knowing a property
 * name is not a dependency.
 */
public class LocalStackS3Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        LocalStackContainer localStack = Containers.localStack();
        TestPropertyValues.of(
                        "ludwig.storage.type=s3",
                        "ludwig.storage.s3.endpoint=" + localStack.getEndpoint(),
                        "ludwig.storage.s3.region=" + localStack.getRegion(),
                        // Path style, because the container is reached by host and port and
                        // `bucket.localhost` does not resolve. The same switch a self-hosted store needs.
                        "ludwig.storage.s3.path-style-access=true",
                        // LocalStack's published constants for an ephemeral container on this machine:
                        // static credentials in the one situation the starter says they are for, not a
                        // counterexample to the platform's credentials rule.
                        "ludwig.storage.s3.credentials.source=static",
                        "ludwig.storage.s3.credentials.access-key=" + localStack.getAccessKey(),
                        "ludwig.storage.s3.credentials.secret-key=" + localStack.getSecretKey())
                .applyTo(context);
    }
}
