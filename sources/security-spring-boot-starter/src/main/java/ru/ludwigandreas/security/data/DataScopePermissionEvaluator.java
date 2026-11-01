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
 * @PostAuthorize("hasPermission(returnObject, 'sales.order.read')")
 * public Order get(UUID id) { ... }
 *
 * @PreAuthorize("hasPermission(#order, 'sales.order.update')")
 * public Order update(Order order) { ... }
 * }</pre>
 *
 * <p>The permission is the operation performed, never a role or a bare action: the operation names its resource and
 * action, and the object's class must belong to that resource, found through {@link DataScopeRegistry}; anything else
 * is a denial.
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
    private final ScopeLookup scopes;

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
        String operationId = String.valueOf(permission);
        if (!mapping.get().getResourceType().equals(scopes.resourceOf(operationId))) {
            log.warn("hasPermission called for {} with operation {}, whose rows are not {}; denying",
                    targetDomainObject.getClass().getName(), operationId, mapping.get().getResourceType());
            return false;
        }
        return guard.permits(operationId, targetDomainObject);
    }

    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType,
                                 Object permission) {
        if (authentication == null || !(authentication.getPrincipal() instanceof LudwigPrincipal)) {
            return false;
        }
        String operationId = String.valueOf(permission);
        if (registry.find(targetType).isEmpty() && !registry.isUnscoped(targetType)
                || !targetType.equals(scopes.resourceOf(operationId))) {
            return false;
        }
        DataScope scope = guard.scope(operationId);
        if (!scope.isUnrestricted()) {
            log.debug("Id-based hasPermission on '{}' cannot evaluate a restricted scope without the "
                    + "object; denying. Use hasPermission(returnObject, ...) in @PostAuthorize instead.",
                    targetType);
        }
        return scope.isUnrestricted();
    }
}
