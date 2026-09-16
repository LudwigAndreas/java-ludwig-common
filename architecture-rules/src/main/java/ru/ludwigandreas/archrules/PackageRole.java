package ru.ludwigandreas.archrules;

import java.util.List;
import java.util.Optional;

/**
 * The architectural role a package of the service under analysis plays.
 *
 * <p>Every rule in this library is expressed in terms of roles rather than literal package names, so
 * a consuming service only has to map its own package layout onto these roles once (see
 * {@link ArchitectureConventions}) instead of forking the rules.
 *
 * <p>The values are ArchUnit package identifiers, i.e. {@code ..controller..} matches any package
 * that has a {@code controller} segment anywhere, {@code com.acme.orders.*} matches direct
 * sub-packages, and a literal name matches exactly.
 */
public enum PackageRole {

    /** REST entry points: {@code @Controller}/{@code @RestController} classes. */
    CONTROLLER("controller", "..controller..", "..web..", "..rest.."),

    /** The transaction script / use case layer that owns business behaviour. */
    SERVICE("service", "..service..", "..application..", "..usecase.."),

    /** Data access: Spring Data repositories and hand-written persistence adapters. */
    REPOSITORY("repository", "..repository..", "..persistence..", "..dao.."),

    /** JPA entities - the only place {@code @Entity} is allowed to appear. */
    ENTITY("entity", "..entity..", "..entities.."),

    /**
     * Framework-free domain model of a hexagonal service. Empty by default because it only exists in
     * services that keep the domain separate from the persistence entities; see
     * {@link RuleGroup#DOMAIN_ISOLATION}.
     */
    DOMAIN("domain", "..domain.."),

    /** Request/response models that cross the REST boundary. */
    DTO("dto", "..dto..", "..request..", "..response.."),

    /**
     * Translation between two layers' models - typically MapStruct mappers. A mapper sits next to
     * the layer it serves ({@code ..web.mapper..}) but belongs to neither: by definition it touches
     * the models on both sides, so it is excluded from the layer it lives in rather than being
     * treated as an entry point of it.
     */
    MAPPER("mapper", "..mapper..", "..mappers.."),

    /** Spring {@code @Configuration}/{@code @ConfigurationProperties} classes. */
    CONFIGURATION("configuration", "..config..", "..configuration.."),

    /** Kafka producers and consumers. */
    MESSAGING("messaging", "..messaging..", "..kafka.."),

    /** Kafka message/event payload types. */
    EVENT_PAYLOAD("event-payload", "..event..", "..events.."),

    /** Adapters around external object storage (S3 and friends). */
    STORAGE("storage", "..storage..", "..s3.."),

    /**
     * Where {@code @Transactional} may be declared. Defaults to the service packages; split out so a
     * service that demarcates transactions in a dedicated facade package can say so.
     */
    TRANSACTIONAL_HOST("transactional-host", "..service..", "..application..", "..usecase.."),

    /**
     * Name of the package segment that marks a module's internals, e.g. {@code internal} in
     * {@code com.acme.orders.internal.jpa}. Unlike every other role this holds bare segment names,
     * not package identifiers, because the module it belongs to is derived from where the segment
     * appears. Configure several names if more than one convention is in use.
     */
    MODULE_INTERNAL("module-internal", "internal"),

    /**
     * Name of the package segment that holds a module's public API, e.g. {@code api} in
     * {@code com.acme.orders.api}. Bare segment names, like {@link #MODULE_INTERNAL}.
     */
    MODULE_API("module-api", "api");

    private final String id;
    private final List<String> defaultPackageIdentifiers;

    PackageRole(String id, String... defaultPackageIdentifiers) {
        this.id = id;
        this.defaultPackageIdentifiers = List.of(defaultPackageIdentifiers);
    }

    /** Stable kebab-case identifier used in property keys, e.g. {@code event-payload}. */
    public String id() {
        return id;
    }

    public List<String> defaultPackageIdentifiers() {
        return defaultPackageIdentifiers;
    }

    public static Optional<PackageRole> byId(String id) {
        return Enums.byId(values(), PackageRole::id, id);
    }
}
