package ru.ludwigandreas.testsupport.local;

import org.testcontainers.containers.GenericContainer;
import ru.ludwigandreas.testsupport.container.Containers;

/**
 * The backing services a local application can name.
 *
 * <p>Each is {@code test-support}'s digest-pinned singleton - this module declares no image of its
 * own. A service is started only when it is named: a service with {@code spring-kafka} on its
 * classpath and Kafka switched off by configuration is ordinary on this platform, so the classpath
 * does not say what a service needs and nothing here infers it.
 */
public enum BackingService {

    /** PostgreSQL, connected through Spring Boot connection details for JDBC and Liquibase. */
    POSTGRES("org.testcontainers.containers.PostgreSQLContainer", "org.testcontainers:postgresql"),

    /** Kafka, published as {@code spring.kafka.bootstrap-servers}. */
    KAFKA("org.testcontainers.kafka.KafkaContainer", "org.testcontainers:kafka"),

    /** LocalStack S3, published as {@code ludwig.storage.s3.*}. */
    LOCALSTACK("org.testcontainers.containers.localstack.LocalStackContainer", "org.testcontainers:localstack"),

    /** Redis, published as {@code spring.data.redis.host} and {@code spring.data.redis.port}. */
    REDIS("org.testcontainers.containers.GenericContainer", "org.testcontainers:testcontainers");

    private final String requiredClass;
    private final String dependency;

    BackingService(String requiredClass, String dependency) {
        this.requiredClass = requiredClass;
        this.dependency = dependency;
    }

    /**
     * The class whose presence means this service's Testcontainers module is on the classpath.
     *
     * @return a fully qualified class name
     */
    public String requiredClass() {
        return requiredClass;
    }

    /**
     * The dependency a service declares, at test scope, to be able to name this service.
     *
     * @return {@code groupId:artifactId}
     */
    public String dependency() {
        return dependency;
    }

    /**
     * Where the started service is reachable. Starts it if nothing has yet.
     *
     * @return a JDBC URL, bootstrap servers, an endpoint or {@code host:port}
     */
    public String address() {
        return switch (this) {
            case POSTGRES -> Containers.postgres().getJdbcUrl();
            case KAFKA -> Containers.kafka().getBootstrapServers();
            case LOCALSTACK -> Containers.localStack().getEndpoint().toString();
            case REDIS -> {
                GenericContainer<?> redis = Containers.redis();
                yield redis.getHost() + ":" + redis.getMappedPort(Containers.REDIS_PORT);
            }
        };
    }
}
