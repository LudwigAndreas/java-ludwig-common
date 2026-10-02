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
 *
 * <h2>The boundary with revapi: no group here may be about API removal</h2>
 *
 * <p><b>A rule about how the API differs from a published baseline belongs to revapi, and ArchUnit
 * must not grow a rule about API removal.</b> The reason is not taste: ArchUnit analyses the
 * classes on the current classpath and has no access to the previous release, so
 * "{@code AuditSink.record} existed in 1.2.0" is not a statement it is able to make. A group added
 * here to police removals could only ever check the shape of what is present, which is a different
 * question that happens to read like the same one - and the version that would look checked is the
 * one nobody checked.
 *
 * <p>revapi is configured in the reactor root POM and runs at {@code verify} for every published
 * library. The removal window it in turn cannot check - that a deprecated API survive one minor
 * release before it goes - is recorded beside that configuration, for the same reason this
 * paragraph is recorded here: a limit that is only understood is a limit that is crossed.
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

    /**
     * There is one audit trail, and nobody declares a second one.
     *
     * <p>On by default, and vacuous for a service that audits nothing - it then simply has no audit SPI
     * to find. See {@link ru.ludwigandreas.archrules.rules.AuditRules} for what this group deliberately
     * leaves to Checkstyle and why.
     */
    AUDIT("audit", true),

    /**
     * There is one vocabulary for long-running operations, and nobody declares a second one.
     *
     * <p>On by default, and vacuous for a module with no run state of its own - it then simply has no
     * enum to find. See {@link ru.ludwigandreas.archrules.rules.OperationVocabularyRules} for what
     * separates a second vocabulary from a richer domain lifecycle, which is the distinction the
     * whole rule turns on.
     */
    OPERATIONS("operations", true),

    /**
     * There is one caching primitive, and nobody builds a second one.
     *
     * <p>On by default, and vacuous for a module that caches nothing - it then has no Caffeine dependency and
     * no cache SPI to find. See {@link ru.ludwigandreas.archrules.rules.CachingRules} for what this group
     * deliberately leaves to the cache module's own startup validator and why.
     */
    CACHING("caching", true),

    /**
     * There is one answer to what locale and zone a caller reads in, and nobody asks the JVM.
     *
     * <p>On by default, and vacuous for a module that presents nothing - it then calls no JVM-default accessor
     * and declares no preference type. See {@link ru.ludwigandreas.archrules.rules.PresentationRules} for what
     * separates a second caller-preference type from a per-subject lookup, which is the distinction the second
     * rule turns on, and for the two things in this area that deliberately cannot be checked.
     */
    PRESENTATION("presentation", true),

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
