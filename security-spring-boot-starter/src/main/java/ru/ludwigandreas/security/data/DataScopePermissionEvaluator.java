package ru.ludwigandreas.security.data;

import java.io.Serializable;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * Bridges Spring Security's {@code hasPermission(...)} expression to this module's data scopes, so the
 * post-check can be written as an annotation instead of a call.
 *
 * <pre>{@code
 * @PostAuthorize("hasPermission(returnObject, 'read')")
 * public Order get(UUID id) { ... }
 *
 * @PreAuthorize("hasPermission(#order, 'write')")
 * public Order update(Order order) { ... }
 * }</pre>
 *
 * <p>The resource type is inferred from the object's class through {@link DataScopeRegistry}, so the
 * annotation stays readable and a resource rename does not have to be chased through expression
 * strings.
 *
 * <p><b>The id-based overload is deliberately weak.</b> Given only an id and a type name there is no
 * object to test a scope against, and loading one here would issue a second, unaudited read from
 * inside an expression. It therefore grants only when the caller's scope is unrestricted anyway. Use
 * the object form - which is also why {@code @PostAuthorize} is the right annotation for reads: the
 * object has to exist before it can be judged.
 *
 * <p>An unmapped class is a denial. Erring the other way would make every {@code hasPermission} call
 * against a forgotten mapping return true, which is worse than the annotation appearing not to work.
 */
@Slf4j
@RequiredArgsConstructor
public class DataScopePermissionEvaluator implements PermissionEvaluator {

    private final DataAccessGuard guard;
    private final DataScopeRegistry registry;

    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (authentication == null || targetDomainObject == null
                || !(authentication.getPrincipal() instanceof LudwigPrincipal)) {
            return false;
        }
        Optional<DataScopeMapping<Object>> mapping = registry.findByClass(targetDomainObject.getClass());
        if (mapping.isEmpty()) {
            log.warn("hasPermission called for {} but no DataScopeMapping is registered for it; denying",
                    targetDomainObject.getClass().getName());
            return false;
        }
        return guard.permits(mapping.get().getResourceType(), String.valueOf(permission), targetDomainObject);
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType,
                                 Object permission) {
        if (authentication == null || !(authentication.getPrincipal() instanceof LudwigPrincipal)) {
            return false;
        }
        if (registry.find(targetType).isEmpty() && !registry.isUnscoped(targetType)) {
            return false;
        }
        DataScope scope = guard.scope(targetType, String.valueOf(permission));
        if (!scope.isUnrestricted()) {
            log.debug("Id-based hasPermission on '{}' cannot evaluate a restricted scope without the "
                    + "object; denying. Use hasPermission(returnObject, ...) in @PostAuthorize instead.",
                    targetType);
        }
        return scope.isUnrestricted();
    }
}
