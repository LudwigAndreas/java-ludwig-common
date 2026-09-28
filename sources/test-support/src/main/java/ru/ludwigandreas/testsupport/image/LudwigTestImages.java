package ru.ludwigandreas.testsupport.image;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Pattern;
import org.testcontainers.utility.DockerImageName;

/**
 * Every container image this platform's tests run, as typed constants, loaded from
 * {@code images.properties} on this jar's classpath.
 *
 * <h2>Why this type exists</h2>
 *
 * <p>The platform's security policy is that every image is identified by name, version <em>and</em>
 * {@code sha256} digest, and that policy is correct: a tag can be re-pointed at different content, so
 * a test - and everything else that runs a container - would silently change what it executes.
 *
 * <p>The consequence nobody priced in is that the digest then has to be written down wherever a
 * container is started. Before this class, one postgres literal appeared in twenty-one source files
 * in three different layouts: on one line, split across two with a string concatenation, and split
 * plus fully qualified. A {@code sed} for the full literal finds the first spelling and silently
 * misses the other two, which leaves half the build on one digest and half on another - and the next
 * CVE respin of {@code postgres:16-alpine} was a twenty-one file edit somebody had to remember.
 *
 * <h2>Why a properties resource rather than constants in this source file</h2>
 *
 * <p>The values live in {@code images.properties} so that the {@code # renovate:} comments beside them
 * let a dependency bot raise a one-line pull request for the next digest. Constants here would have
 * fixed the duplication but left the upgrade manual; the resource fixes both.
 *
 * <h2>Why initialisation fails loudly</h2>
 *
 * <p>A missing key, or a value without a digest, throws from the static initialiser rather than
 * falling back to a bare tag. A quiet fallback would reintroduce the exact policy violation this
 * class exists to prevent, and it would do it invisibly: the tests would still pass, against an
 * image nobody chose.
 *
 * <h2>{@code asCompatibleSubstituteFor} is already applied</h2>
 *
 * <p>Testcontainers refuses an image whose name it does not recognise as the one a container class
 * expects, and a name carrying a digest is never recognised. Every call site therefore had to add
 * {@code asCompatibleSubstituteFor}, and one of them forgot. It is applied here, once.
 *
 * @see ru.ludwigandreas.testsupport.container.Containers
 */
public final class LudwigTestImages {

    /**
     * The resource, at the root of this jar.
     *
     * <p>Read through {@code LudwigTestImages.class} rather than the thread context class loader so
     * that it resolves from this jar regardless of how the surrounding test runner is arranged.
     */
    private static final String RESOURCE = "/images.properties";

    /** A pinned reference: anything, then {@code @sha256:}, then exactly 64 lowercase hex digits. */
    private static final Pattern PINNED = Pattern.compile("^\\S+@sha256:[0-9a-f]{64}$");

    private static final Properties VALUES = load();

    /**
     * PostgreSQL, the database every service and eleven of the library modules test against.
     *
     * <p>Substitutable for {@code postgres}, which is what {@code PostgreSQLContainer} checks.
     */
    public static final DockerImageName POSTGRES = image("postgres", "postgres");

    /**
     * Apache Kafka, in the broker's own image rather than Confluent's.
     *
     * <p>Substitutable for {@code apache/kafka}, which is what {@code KafkaContainer} checks.
     */
    public static final DockerImageName KAFKA = image("kafka", "apache/kafka");

    /**
     * LocalStack, which stands in for S3.
     *
     * <p>LocalStack and not MinIO, for the reason {@code object-storage-spring-boot-starter}'s
     * {@code S3ObjectStoreIT} documents at length and which this move must not throw away: MinIO's
     * images can no longer be pulled anonymously from Docker Hub or quay.io, so there is no digest
     * this repository could pin, and an unpinned MinIO would be a worse outcome than a different,
     * pinnable S3 implementation. LocalStack's S3 implements everything the storage contract
     * exercises - ranged GETs, {@code 416} past the end, continuation-token paging, {@code CopyObject}
     * and content-derived etags.
     *
     * <p>An estate that would rather test against MinIO, and has a registry it can pull a digest
     * from, changes one line in {@code images.properties} and nothing else.
     */
    public static final DockerImageName LOCALSTACK = image("localstack", "localstack/localstack");

    /**
     * Redis, for {@code cache-spring-boot-starter}'s shared tier.
     *
     * <p>Substitutable for {@code redis}. Started by {@link ru.ludwigandreas.testsupport.container.Containers}
     * only for the tests that exercise the shared tier - the local Caffeine tier needs no container, which
     * keeps the cache module's suite runnable without Docker.
     */
    public static final DockerImageName REDIS = image("redis", "redis");

    private LudwigTestImages() {
    }

    /**
     * The raw, digest-pinned reference behind one of the constants above.
     *
     * <p>For the rare caller that needs the string rather than a {@code DockerImageName} - a README
     * generator, or a {@code docker run} line in a diagnostic message.
     *
     * @param key the short name under {@code ludwig.test.image.}, e.g. {@code postgres}
     * @return the pinned reference
     * @throws IllegalArgumentException if no such key is pinned
     */
    public static String reference(String key) {
        String value = VALUES.getProperty("ludwig.test.image." + key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "No image pinned under ludwig.test.image." + key + " in " + RESOURCE);
        }
        return value.trim();
    }

    private static DockerImageName image(String key, String substituteFor) {
        String reference = reference(key);
        if (!PINNED.matcher(reference).matches()) {
            throw new IllegalStateException(
                    "Image ludwig.test.image." + key + " in " + RESOURCE + " is not pinned by digest: '"
                            + reference + "'. Company policy is name plus version plus sha256 digest, and a"
                            + " test container is not an exception to it - a tag alone can be re-pointed at"
                            + " different content.");
        }
        return DockerImageName.parse(reference).asCompatibleSubstituteFor(substituteFor);
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream stream = LudwigTestImages.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException(
                        RESOURCE + " is missing from the test-support jar. Nothing can start a container"
                                + " without it, because it is the only place a digest is written down.");
            }
            properties.load(stream);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + RESOURCE, e);
        }
        return properties;
    }
}
