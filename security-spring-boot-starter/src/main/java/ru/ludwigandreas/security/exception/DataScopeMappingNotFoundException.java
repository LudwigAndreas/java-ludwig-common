package ru.ludwigandreas.security.exception;

/**
 * No {@link ru.ludwigandreas.security.data.DataScopeMapping} is registered for a resource type whose
 * access was scoped.
 *
 * <p>This is fail-closed by design. The alternative - treating an unmapped resource as unrestricted -
 * means adding a new entity and forgetting to map it silently publishes every row of it, which is
 * exactly the mistake this module exists to make impossible.
 */
public class DataScopeMappingNotFoundException extends RuntimeException {

    public DataScopeMappingNotFoundException(String resourceType) {
        super("No DataScopeMapping registered for resource type '" + resourceType + "'. "
                + "Register a DataScopeMapping bean for it, or exclude the resource from data scoping "
                + "with ludwig.security.data.unscoped-resources.");
    }
}
