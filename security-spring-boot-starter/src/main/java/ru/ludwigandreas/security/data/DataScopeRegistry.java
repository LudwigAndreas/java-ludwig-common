package ru.ludwigandreas.security.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import ru.ludwigandreas.security.exception.DataScopeMappingNotFoundException;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Every {@link DataScopeMapping} in the context, indexed by resource name and by class.
 *
 * <p>The class index is what lets {@code @PostAuthorize("hasPermission(returnObject, 'read')")} work
 * without naming the resource: the evaluator gets an object and has to find its policy. Lookups walk
 * the superclass chain, because what a repository returns is usually a Hibernate proxy subclass, not
 * the mapped class itself.
 */
public class DataScopeRegistry {

    private final Map<String, DataScopeMapping<?>> byResourceType = new LinkedHashMap<>();
    private final Map<Class<?>, DataScopeMapping<?>> byResourceClass = new LinkedHashMap<>();
    private final Set<String> unscopedResources;

    public DataScopeRegistry(List<DataScopeMapping<?>> mappings, Set<String> unscopedResources) {
        this.unscopedResources = Set.copyOf(unscopedResources);
        for (DataScopeMapping<?> mapping : mappings) {
            DataScopeMapping<?> clash = byResourceType.put(mapping.getResourceType(), mapping);
            if (clash != null) {
                throw new SecurityConfigurationException("Two DataScopeMapping beans claim resource type '"
                        + mapping.getResourceType() + "' (" + clash.getResourceClass().getName() + " and "
                        + mapping.getResourceClass().getName() + "). Resource names must be unique - "
                        + "otherwise which policy applies depends on bean ordering.");
            }
            byResourceClass.put(mapping.getResourceClass(), mapping);
        }
    }

    /**
     * @throws DataScopeMappingNotFoundException when the resource is neither mapped nor explicitly
     *                                           declared unscoped - failing closed on the assumption
     *                                           that an unmapped resource is an oversight
     */
    @SuppressWarnings("unchecked")
    public <T> DataScopeMapping<T> require(String resourceType) {
        DataScopeMapping<?> mapping = byResourceType.get(resourceType);
        if (mapping == null) {
            throw new DataScopeMappingNotFoundException(resourceType);
        }
        return (DataScopeMapping<T>) mapping;
    }

    public Optional<DataScopeMapping<?>> find(String resourceType) {
        return Optional.ofNullable(byResourceType.get(resourceType));
    }

    /** True for resources deliberately exempt from scoping (reference data, public catalogs). */
    public boolean isUnscoped(String resourceType) {
        return unscopedResources.contains(resourceType);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<DataScopeMapping<T>> findByClass(Class<?> candidate) {
        for (Class<?> current = candidate; current != null && current != Object.class;
                current = current.getSuperclass()) {
            DataScopeMapping<?> mapping = byResourceClass.get(current);
            if (mapping != null) {
                return Optional.of((DataScopeMapping<T>) mapping);
            }
        }
        return Optional.empty();
    }

    public Set<String> resourceTypes() {
        return Set.copyOf(byResourceType.keySet());
    }
}
