package ru.ludwigandreas.odatafilter.metadata;

import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import ru.ludwigandreas.odatafilter.annotation.FilterPolicy;

/**
 * Which published names exist, and which entity each one means.
 *
 * <h2>Built from the JPA metamodel, not from a list a service maintains</h2>
 *
 * <p>A consumer registers nothing. At startup this walks {@link Metamodel#getEntities()}, keeps the Java
 * types whose {@code @FilterPolicy} declares a {@code metadataName}, and that is the index. The
 * alternative - a {@code FilterMetadataSource} bean in which each service lists its published entity
 * classes - is exactly the kind of per-service registration list this module exists to delete, and it can
 * silently disagree with the annotations it duplicates.
 *
 * <p>The consequence is that the metadata endpoint needs a persistence unit, which is correct: an entity
 * with no persistence unit has no endpoint to describe. {@code ODataFilterService} keeps working without
 * one - {@code export-spring-boot-starter} parses a requester's filter on a worker thread with no
 * {@code EntityManager} - which is why this lives behind its own auto-configuration.
 *
 * <h2>Why the document is not built here</h2>
 *
 * <p><b>This class resolves a name to a class and nothing else.</b> The document itself is
 * {@link FilterMetadataFactory}'s projection of the resolved {@code EntityFilterPolicy}, which is a
 * projection of the entity's own annotations. Any field list, type list or operator list declared beside
 * those annotations - here or anywhere - is the defect this comment exists to prevent: two descriptions of
 * one policy drift, and they drift invisibly in exactly the cases that matter, which are a field opened in
 * code and still absent from the document and a field closed in code and still advertised.
 */
public class FilterMetadataRegistry {

    private final Map<String, Class<?>> byPublishedName;

    /**
     * Indexes every entity in the metamodel that declares a published name.
     *
     * @param metamodel the application's JPA metamodel
     * @throws IllegalStateException if two entities publish the same name, naming both
     */
    public FilterMetadataRegistry(Metamodel metamodel) {
        Map<String, Class<?>> index = new TreeMap<>();
        for (EntityType<?> entityType : metamodel.getEntities()) {
            Class<?> javaType = entityType.getJavaType();
            if (javaType == null) {
                continue;
            }
            publishedName(javaType).ifPresent(name -> {
                Class<?> existing = index.put(name, javaType);
                if (existing != null && !existing.equals(javaType)) {
                    throw new IllegalStateException(
                            ("Two entities publish their filter policy as '%s': %s and %s. A published name is "
                                    + "a URL, so it has to identify one entity. Give one of them a different "
                                    + "@FilterPolicy(metadataName = ...).")
                                    .formatted(name, existing.getName(), javaType.getName()));
                }
            });
        }
        this.byPublishedName = new LinkedHashMap<>(index);
    }

    private static Optional<String> publishedName(Class<?> javaType) {
        FilterPolicy policy = javaType.getAnnotation(FilterPolicy.class);
        if (policy == null || policy.metadataName().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(policy.metadataName().trim());
    }

    /**
     * The entity published under {@code name}, or empty if nothing is.
     *
     * <p>Empty covers both "no such entity" and "that entity exists and publishes nothing", deliberately:
     * telling them apart would make this endpoint a way to enumerate the entity model, which is the same
     * conflation the module already makes between "no such field" and "that field is not filterable".
     */
    public Optional<Class<?>> entityFor(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(byPublishedName.get(name.trim()));
    }

    /** Every published name, sorted. Used by tests and by an index endpoint if one is ever added. */
    public Set<String> publishedNames() {
        return Set.copyOf(byPublishedName.keySet());
    }

    /** How many entities publish, for a startup log line that says whether anything is exposed. */
    public int size() {
        return byPublishedName.size();
    }
}
