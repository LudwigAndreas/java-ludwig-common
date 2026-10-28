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

    /**
     * There is one way a user-submitted file reaches the platform, and nobody writes a second one.
     *
     * <p>On by default, and vacuous for a module with no upload endpoint - it then has no
     * {@code MultipartFile} to find. See {@link ru.ludwigandreas.archrules.rules.UploadRules} for what the
     * rule covers and the three things it deliberately cannot check.
     */
    UPLOADS("uploads", true),

    /**
     * There is one way a long-lived credential is stored, read and attenuated, and nobody writes a second one.
     *
     * <p>On by default, and vacuous for a module that issues no credential - it then has no secret carrier and
     * no credential store to find. See {@link ru.ludwigandreas.archrules.rules.CredentialRules} for the four
     * rules, for the two things in this area that deliberately cannot be checked, and for the check that is
     * deliberately <em>deferred</em> rather than written as a rule whose matching set is empty.
     *
     * <h2>The distinction this group turns on</h2>
     *
     * <p>A second credential <b>format</b> is legitimate and expected: a deploy key, a signed webhook secret
     * and a personal access token are different credentials with different lifetimes, different issuance
     * authorities and different revocation stories, and collapsing them would be the mistake rather than the
     * fix. What must not exist twice is the <b>mechanism</b> - the store a credential digest lives in, the
     * seam a deployment implements to supply its own, and above all the code path that turns a credential
     * into authority.
     *
     * <p>The group is therefore named for the concern and not for the feature. A future deploy-key module
     * declares its own format in its own package and reuses this mechanism; it does not get its own rule
     * group, and it does not get its own answer to "how does a credential become a principal".
     */
    CREDENTIALS("credentials", true),

    /**
     * There is one log pipeline and one statement of what a build was made from, and nobody writes a second.
     *
     * <p>On by default, and vacuous for a module that shapes no log output and launches no process - it then
     * has no Logback encoder, no provenance record and no {@code ProcessBuilder} to find. See
     * {@link ru.ludwigandreas.archrules.rules.LoggingRules} for the three rules and for the two things in
     * this area that a build cannot check.
     *
     * <h2>The distinction this group turns on</h2>
     *
     * <p>A second encoder <b>implementation</b> is forbidden; a service's own logging <b>configuration</b> is
     * not. A module that declares a type implementing Logback's {@code Encoder} or {@code Layout} publishes a
     * second field vocabulary for other modules to write through, and two vocabularies disagree silently
     * until the aggregator's index has the wrong mapping for one of them. A service that attaches an appender
     * in its own {@code logback-spring.xml} is choosing its own output: it affects that service alone, the
     * observability starter deliberately leaves such an appender as it found it, and forbidding the file
     * would make a reasoned escape hatch unreachable. The group therefore polices a type, never a file.
     */
    LOGGING("logging", true),

    /**
     * There is one way a service exposes or calls a gRPC service, and nobody assembles a second by hand.
     *
     * <p>On by default, and vacuous for a module that speaks no gRPC - it then references no channel or server
     * builder and declares no service implementation to find. See
     * {@link ru.ludwigandreas.archrules.rules.GrpcRules} for the four rules and for the five things in this
     * area that a build cannot check.
     *
     * <h2>The distinction this group turns on</h2>
     *
     * <p>A gRPC <b>service</b> is an adapter and a gRPC <b>channel</b> is configuration; neither is something a
     * module builds. What a module legitimately writes is the adapter's body - convert the message, call the
     * application service, convert the answer - and a {@code ludwig.grpc.client.clients.<name>} block. What it
     * must not write is the thing around either: a {@code ManagedChannelBuilder} with its own deadline,
     * credentials and retry, or a {@code ServerBuilder} with its own interceptors, because those are the
     * decisions {@code grpc-client-spring-boot-starter} and {@code grpc-server-spring-boot-starter} exist to
     * take once.
     *
     * <p>The group also forbids something that looks harmless and is not: a proxy-based annotation on a gRPC
     * service method. gRPC dispatches to the object {@code bindService()} captured, not through the Spring
     * proxy around the bean, so {@code @PreAuthorize} or {@code @Transactional} there compiles, starts and
     * does nothing. A rule is the only place that failure can be made loud.
     */
    GRPC("grpc", true),

    /**
     * The administrative surface: every handler under {@code /admin/} declares its operation with
     * {@code @AdminAccess} (or {@code @AdminAuthenticated}), carries no second authorization annotation, is mapped
     * under {@code /admin/v1/}, and delegates to a service rather than a repository. See
     * {@link ru.ludwigandreas.archrules.rules.AdminRules} for the rules and for the three things in this area no
     * bytecode rule can see. Vacuous for a module with no handler under {@code /admin/}.
     */
    ADMIN("admin", true),

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
