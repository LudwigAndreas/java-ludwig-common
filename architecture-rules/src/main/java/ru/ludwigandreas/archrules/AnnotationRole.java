package ru.ludwigandreas.archrules;

import java.util.List;
import java.util.Optional;

/**
 * Annotations the rules recognise, by fully qualified name.
 *
 * <p>Names rather than {@code Class} literals: this library must not force jakarta.persistence,
 * Spring Kafka or spring-tx onto the classpath of a service that does not use them, and a service is
 * free to add its own meta-annotation (a {@code @DomainEntity} of its own that carries
 * {@code @Entity}) to the relevant role. Every check considers meta-annotations too, so
 * {@code @RestController} is recognised through {@code @Controller}.
 */
public enum AnnotationRole {

    /** Marks a REST entry point. */
    CONTROLLER("controller",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController"),

    /** Marks a persistent type: entity, mapped superclass or embeddable. */
    PERSISTENT_TYPE("persistent-type",
            "jakarta.persistence.Entity",
            "jakarta.persistence.MappedSuperclass",
            "jakarta.persistence.Embeddable",
            "javax.persistence.Entity",
            "javax.persistence.MappedSuperclass",
            "javax.persistence.Embeddable"),

    /** Marks a data access component. */
    REPOSITORY("repository", "org.springframework.stereotype.Repository"),

    /** Marks a class that contributes bean definitions or typed configuration. */
    CONFIGURATION("configuration",
            "org.springframework.context.annotation.Configuration",
            "org.springframework.boot.autoconfigure.AutoConfiguration",
            "org.springframework.boot.context.properties.ConfigurationProperties",
            "org.springframework.boot.context.properties.EnableConfigurationProperties"),

    /** Declares transaction demarcation. */
    TRANSACTIONAL("transactional",
            "org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional",
            "javax.transaction.Transactional"),

    /** Injects a dependency into a field instead of a constructor. */
    FIELD_INJECTION("field-injection",
            "org.springframework.beans.factory.annotation.Autowired",
            "jakarta.inject.Inject",
            "javax.inject.Inject",
            "jakarta.annotation.Resource",
            "javax.annotation.Resource"),

    /**
     * Marks the application entry point. Spring Boot's {@code @SpringBootApplication} is a
     * {@code @Configuration} by meta-annotation, but it belongs in the root package by convention,
     * so the rules that place configuration classes exempt it.
     */
    APPLICATION("application",
            "org.springframework.boot.autoconfigure.SpringBootApplication",
            "org.springframework.boot.SpringBootConfiguration"),

    /**
     * Marks a JPA entity specifically - narrower than {@link #PERSISTENT_TYPE}, because a
     * {@code @MappedSuperclass} is usually the audit base class itself and cannot be required to
     * extend it.
     */
    ENTITY("entity", "jakarta.persistence.Entity", "javax.persistence.Entity"),

    /** Marks a REST controller specifically, as opposed to an MVC {@code @Controller}. */
    REST_CONTROLLER("rest-controller", "org.springframework.web.bind.annotation.RestController"),

    /** Marks typed configuration bound from the environment. */
    CONFIGURATION_PROPERTIES("configuration-properties",
            "org.springframework.boot.context.properties.ConfigurationProperties"),

    /** Marks a class whose bound values are validated at startup. */
    VALIDATED("validated",
            "org.springframework.validation.annotation.Validated",
            "jakarta.validation.Valid"),

    /** Marks the central exception translation point of the web layer. */
    CONTROLLER_ADVICE("controller-advice",
            "org.springframework.web.bind.annotation.ControllerAdvice",
            "org.springframework.web.bind.annotation.RestControllerAdvice"),

    /**
     * Marks a bean that Spring keeps one instance of for the whole application. Advice classes are
     * included deliberately: they are as singleton-scoped as a {@code @Service}, and as prone to
     * accumulating shared mutable state.
     */
    SINGLETON_BEAN("singleton-bean",
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.context.annotation.Configuration",
            "org.springframework.web.bind.annotation.ControllerAdvice",
            "org.springframework.web.bind.annotation.RestControllerAdvice"),

    /** Marks a generated mapper interface. */
    MAPPER("mapper", "org.mapstruct.Mapper"),

    /** Declares the HTTP path a controller or handler method is mounted at. */
    REQUEST_MAPPING("request-mapping", "org.springframework.web.bind.annotation.RequestMapping"),

    /** Marks a Kafka consumer entry point. */
    KAFKA_LISTENER("kafka-listener",
            "org.springframework.kafka.annotation.KafkaListener",
            "org.springframework.kafka.annotation.KafkaHandler"),

    /** Marks a Spring bean. Used to scope the constructor injection rule. */
    SPRING_COMPONENT("spring-component",
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.context.annotation.Configuration");

    private final String id;
    private final List<String> defaultAnnotationNames;

    AnnotationRole(String id, String... defaultAnnotationNames) {
        this.id = id;
        this.defaultAnnotationNames = List.of(defaultAnnotationNames);
    }

    public String id() {
        return id;
    }

    public List<String> defaultAnnotationNames() {
        return defaultAnnotationNames;
    }

    public static Optional<AnnotationRole> byId(String id) {
        return Enums.byId(values(), AnnotationRole::id, id);
    }
}
