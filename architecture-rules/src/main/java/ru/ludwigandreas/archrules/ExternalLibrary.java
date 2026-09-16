package ru.ludwigandreas.archrules;

import java.util.List;
import java.util.Optional;

/**
 * Third-party technology whose packages the rules reason about ("no class outside the storage
 * adapter may depend on the AWS SDK", "the domain must not see Spring").
 *
 * <p>Each value carries the package identifiers that identify the library. They are configurable so
 * a service can point a rule at a different SDK, an internal fork, or an additional JSON library
 * without changing this library.
 */
public enum ExternalLibrary {

    /** The Spring ecosystem as a whole - used by the domain isolation rules. */
    SPRING_FRAMEWORK("spring", "org.springframework.."),

    /** JPA/Hibernate: annotations and API types that must not leak out of persistence. */
    PERSISTENCE_API("persistence-api", "jakarta.persistence..", "javax.persistence..", "org.hibernate.."),

    /** JSON binding libraries that must not dictate the shape of a framework-free domain model. */
    JSON("json", "com.fasterxml.jackson..", "com.google.gson..", "org.codehaus.jackson..", "jakarta.json.."),

    /** Kafka client and Spring Kafka. */
    KAFKA("kafka", "org.apache.kafka..", "org.springframework.kafka.."),

    /** AWS SDK v2 and v1, i.e. the S3 client that has to stay behind a storage abstraction. */
    AWS_SDK("aws-sdk", "software.amazon.awssdk..", "com.amazonaws.."),

    /** Test-only frameworks that must never be reachable from production code. */
    TEST_FRAMEWORK("test-framework",
            "org.junit..",
            "junit..",
            "org.mockito..",
            "org.assertj..",
            "org.hamcrest..",
            "org.testcontainers..",
            "io.cucumber..",
            "org.springframework.test..",
            "org.springframework.boot.test..",
            "com.tngtech.archunit.."),

    /** Raw JDBC/JDBC template access, which belongs in the persistence layer if anywhere. */
    JDBC("jdbc", "org.springframework.jdbc..", "java.sql..", "javax.sql..");

    private final String id;
    private final List<String> defaultPackageIdentifiers;

    ExternalLibrary(String id, String... defaultPackageIdentifiers) {
        this.id = id;
        this.defaultPackageIdentifiers = List.of(defaultPackageIdentifiers);
    }

    public String id() {
        return id;
    }

    public List<String> defaultPackageIdentifiers() {
        return defaultPackageIdentifiers;
    }

    public static Optional<ExternalLibrary> byId(String id) {
        return Enums.byId(values(), ExternalLibrary::id, id);
    }
}
