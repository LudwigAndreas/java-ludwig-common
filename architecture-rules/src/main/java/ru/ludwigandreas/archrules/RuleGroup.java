package ru.ludwigandreas.archrules;

import java.util.List;
import java.util.Optional;

/**
 * A family of rules that can be switched on or off as a unit.
 *
 * <p>A group is the coarse toggle ("this service has no Kafka"); individual rules inside it remain
 * separately toggleable by id - see {@link RuleSelection}.
 *
 * <p>{@link #enabledByDefault()} is what a service gets without saying anything. Confinement groups
 * such as {@link #KAFKA} or {@link #STORAGE} are on by default even for services that use neither,
 * because they then simply have nothing to match and pass vacuously. {@link #DOMAIN_ISOLATION} is
 * the exception: it only makes sense for a service that keeps a domain model separate from its
 * persistence entities, so it is opt-in.
 */
public enum RuleGroup {

    /** Controller -> Service -> Repository -> Domain layering. */
    LAYERING("layering", true),

    /** Package cycle detection between and inside modules. */
    CYCLES("cycles", true),

    /** A framework-free domain/core model (hexagonal architecture). Opt-in. */
    DOMAIN_ISOLATION("domain-isolation", false),

    /** What may and may not cross the REST boundary. */
    WEB("web", true),

    /** Where JPA entities, repositories and the persistence context may appear. */
    PERSISTENCE("persistence", true),

    /** Where Kafka producers, consumers and payloads may appear. */
    KAFKA("kafka", true),

    /** Confinement of the object storage SDK behind an internal abstraction. */
    STORAGE("storage", true),

    /** Spring wiring: configuration classes, constructor injection, transaction demarcation. */
    SPRING_WIRING("spring", true),

    /** Module/bounded-context boundaries and their internals. */
    MODULE_BOUNDARY("modules", true),

    /** Production code must not reach into test-only code. */
    TEST_SEPARATION("tests", true),

    /** Environment and system property access confined to configuration classes. */
    CONFIGURATION_ACCESS("configuration", true),

    /** Custom exceptions, and where exceptions may be caught and translated. */
    EXCEPTIONS("exceptions", true),

    /** The API/DTO model is immutable once constructed. */
    DTO_IMMUTABILITY("dto-immutability", true),

    /** Every JPA entity inherits the shared audit base class. */
    ENTITY_BASE("entity-base", true),

    /** Kafka producers and consumers implement the service's own messaging contracts. */
    KAFKA_CONTRACTS("kafka-contracts", true),

    /** Every REST controller is mounted under the organisation's versioned base path. */
    REST_PATHS("rest-paths", true),

    /** Typed configuration is validated at startup. */
    CONFIGURATION_PROPERTIES("configuration-properties", true),

    /** Where {@code Optional} may and may not appear. */
    OPTIONAL_USAGE("optional", true),

    /** Mappers are MapStruct interfaces. */
    MAPPERS("mappers", true),

    /** Rules contributed by the consuming service itself. */
    CUSTOM("custom", true);

    private final String id;
    private final boolean enabledByDefault;

    RuleGroup(String id, boolean enabledByDefault) {
        this.id = id;
        this.enabledByDefault = enabledByDefault;
    }

    /** Stable identifier used in rule ids and property keys, e.g. {@code persistence}. */
    public String id() {
        return id;
    }

    public boolean enabledByDefault() {
        return enabledByDefault;
    }

    public static Optional<RuleGroup> byId(String id) {
        return Enums.byId(values(), RuleGroup::id, id);
    }

    /** All group ids, for error messages that tell the reader what they could have typed. */
    public static List<String> ids() {
        return Enums.ids(values(), RuleGroup::id);
    }
}
