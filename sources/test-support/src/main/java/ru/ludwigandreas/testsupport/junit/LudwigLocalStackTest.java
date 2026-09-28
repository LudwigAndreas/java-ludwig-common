package ru.ludwigandreas.testsupport.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.testsupport.container.LocalStackS3Initializer;
import ru.ludwigandreas.testsupport.container.PostgresContainerConfiguration;

/**
 * Boots an application context against the shared PostgreSQL and the shared LocalStack, with
 * {@code ludwig.storage} pointed at LocalStack's S3.
 *
 * <h2>What this turns on</h2>
 *
 * <p>Everything {@link LudwigPostgresTest} turns on, plus {@link LocalStackS3Initializer}, which
 * contributes the endpoint, region, path-style switch and static credentials that
 * {@code object-storage-spring-boot-starter} reads. An initializer rather than
 * {@code @ServiceConnection} because Spring Boot 3.3 ships no connection-details factory for S3 -
 * that class explains why, and why it is not a {@code @DynamicPropertySource} either.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It does not create a bucket. Bucket names are the module's own vocabulary
 * ({@code partner-drop}, {@code contract-bucket}), and a fixture inventing one would be a name no
 * assertion could predict. Call
 * {@link ru.ludwigandreas.testsupport.container.LocalStackS3#ensureBucket(String)} in a
 * {@code @BeforeEach}, and
 * {@link ru.ludwigandreas.testsupport.container.LocalStackS3#clearBucket(String)} beside it - a shared
 * container means an empty bucket is not free either.
 *
 * <h2>When not to use it</h2>
 *
 * <p>When the test does not exercise real S3 semantics. The storage module ships an in-memory
 * {@code ObjectStore} that satisfies the same contract, and a test about a caller's behaviour should
 * use it. Reach for LocalStack when the assertion is about S3 itself: ranged GETs, {@code 416} past the
 * end of an object, continuation-token paging, {@code CopyObject}, or content-derived etags.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@SpringBootTest
@Import(PostgresContainerConfiguration.class)
@ContextConfiguration(initializers = LocalStackS3Initializer.class)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none"
})
public @interface LudwigLocalStackTest {
}
