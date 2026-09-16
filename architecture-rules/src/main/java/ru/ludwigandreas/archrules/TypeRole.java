package ru.ludwigandreas.archrules;

import java.util.List;
import java.util.Optional;

/**
 * Framework types the rules confine to a layer, by fully qualified name. A class counts as playing
 * the role when it is assignable to any of the listed types, so a service's own
 * {@code JpaRepository} sub-interface is recognised as a Spring Data repository.
 */
public enum TypeRole {

    /** Spring Data repositories, including every {@code CrudRepository}/{@code JpaRepository} heir. */
    SPRING_DATA_REPOSITORY("spring-data-repository", "org.springframework.data.repository.Repository"),

    /** JPA/Hibernate session API - persistence layer only. */
    PERSISTENCE_CONTEXT("persistence-context",
            "jakarta.persistence.EntityManager",
            "jakarta.persistence.EntityManagerFactory",
            "javax.persistence.EntityManager",
            "javax.persistence.EntityManagerFactory",
            "org.hibernate.Session",
            "org.hibernate.SessionFactory"),

    /** Direct SQL access - persistence layer only. */
    JDBC_ACCESS("jdbc-access",
            "org.springframework.jdbc.core.JdbcOperations",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations",
            "javax.sql.DataSource",
            "java.sql.Connection"),

    /**
     * The shared base class every custom exception must extend, e.g.
     * {@code com.acme.common.ApplicationException}. Empty by default: the name belongs to the
     * consuming organisation, and the rule tells a service to configure it rather than guessing.
     */
    BASE_EXCEPTION("base-exception"),

    /**
     * The shared base entity carrying the audit columns (id, createdAt, updatedAt or equivalent),
     * e.g. {@code ru.ludwigandreas.db.core.entity.AuditableEntity}. Empty by default, like
     * {@link #BASE_EXCEPTION}.
     */
    BASE_ENTITY("base-entity"),

    /**
     * The internal contract a Kafka producer must implement, e.g. {@code com.acme.EventPublisher}.
     * Empty by default.
     */
    EVENT_PUBLISHER("event-publisher"),

    /**
     * The internal contract a Kafka consumer must implement, e.g. {@code com.acme.EventHandler}.
     * Empty by default, and the rule is only built once it is configured - plenty of services keep
     * their listeners as plain annotated methods.
     */
    EVENT_CONSUMER("event-consumer"),

    /** {@code Optional} and its primitive specialisations. */
    OPTIONAL("optional",
            "java.util.Optional",
            "java.util.OptionalInt",
            "java.util.OptionalLong",
            "java.util.OptionalDouble"),

    /** Kafka client API - messaging layer only. */
    KAFKA_CLIENT("kafka-client",
            "org.springframework.kafka.core.KafkaTemplate",
            "org.springframework.kafka.core.ProducerFactory",
            "org.springframework.kafka.core.ConsumerFactory",
            "org.apache.kafka.clients.producer.Producer",
            "org.apache.kafka.clients.consumer.Consumer");

    private final String id;
    private final List<String> defaultTypeNames;

    TypeRole(String id, String... defaultTypeNames) {
        this.id = id;
        this.defaultTypeNames = List.of(defaultTypeNames);
    }

    public String id() {
        return id;
    }

    public List<String> defaultTypeNames() {
        return defaultTypeNames;
    }

    public static Optional<TypeRole> byId(String id) {
        return Enums.byId(values(), TypeRole::id, id);
    }
}
