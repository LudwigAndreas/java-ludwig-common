package ru.ludwigandreas.security.admin;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.method.HandlerMethod;
import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * Decides whether a caller holds the operation an administrative handler declares.
 *
 * <p>A denial is published as an {@code AuthorizationDeniedEvent} through the same publisher method security uses,
 * so the existing denial audit and metric record it exactly as they record a refused {@code @PreAuthorize}; the
 * caller is told only that access was denied.
 */
public class AdminAccessEvaluator {

    private final AdminGrants grants;
    private final AuthorizationEventPublisher publisher;
    private final AdminObjectScope objects;

    /**
     * Creates the evaluator.
     *
     * @param grants    the validated grants
     * @param publisher where denials are published
     */
    public AdminAccessEvaluator(AdminGrants grants, AuthorizationEventPublisher publisher) {
        this(grants, publisher, null);
    }

    /**
     * Creates the evaluator with per-object scoping.
     *
     * @param grants    the configured grants
     * @param publisher where denials are published
     * @param objects   which objects a caller's scope admits, or null for no per-object scoping
     */
    public AdminAccessEvaluator(AdminGrants grants, AuthorizationEventPublisher publisher, AdminObjectScope objects) {
        this.grants = grants;
        this.publisher = publisher;
        this.objects = objects;
    }

    /**
     * Refuses the call unless the principal holds the operation.
     *
     * @param authentication the request's authentication
     * @param principal      the caller
     * @param handler        the handler being invoked
     * @param operationId    the operation it declares
     * @throws AccessDeniedException when the caller does not hold the operation
     */
    public void authorize(Authentication authentication, LudwigPrincipal principal, HandlerMethod handler,
                          String operationId) {
        if (grants.holds(principal, operationId)) {
            return;
        }
        refuse(authentication, handler, "Caller does not hold administrative operation " + operationId);
    }

    /**
     * Refuses the call, publishing the denial so that it is audited and metered as every other one is.
     *
     * @param authentication the request's authentication
     * @param handler        what was refused: the handler method, or whatever the dispatcher resolved
     * @param message        why, for the log; the caller is told only that access was denied
     * @throws AccessDeniedException always
     */
    public void refuse(Authentication authentication, Object handler, String message) {
        publishDenial(authentication, handler);
        throw new AccessDeniedException(message);
    }

    /**
     * Refuses the call unless the object its {@link AdminObject} parameter names is in the caller's scope.
     *
     * <p>Read from the matched URI template, before the handler runs. Out of scope is {@code 403} on operate and
     * privileged, and the area's not-found {@code 404} on read so that existence is not disclosed; both are
     * published as denials.
     *
     * @param authentication the request's authentication
     * @param principal      the caller
     * @param handler        the handler being invoked
     * @param operation      the operation it declares
     * @param pathVariables  the request's URI template variables
     */
    public void authorizeObject(Authentication authentication, LudwigPrincipal principal, HandlerMethod handler,
                                AdminOperation operation, Map<String, String> pathVariables) {
        if (objects == null || !objects.isScoped(operation.area(), operation.tier())) {
            return;
        }
        for (MethodParameter parameter : handler.getMethodParameters()) {
            AdminObject declared = parameter.getParameterAnnotation(AdminObject.class);
            if (declared == null) {
                continue;
            }
            String object = pathVariables.get(variableName(parameter));
            if (object != null && objects.admits(principal, operation.area(), operation.tier(), object)) {
                continue;
            }
            publishDenial(authentication, handler);
            if (operation.tier() == AdminTier.READ) {
                throw new AdminObjectNotFoundException(declared.notFound(), object);
            }
            throw new AccessDeniedException("Administrative object '" + object + "' is outside the caller's scope");
        }
    }

    private void publishDenial(Authentication authentication, Object handler) {
        Supplier<Authentication> supplier = () -> authentication;
        publisher.publishAuthorizationEvent(supplier, handler, new AuthorizationDecision(false));
    }

    private static String variableName(MethodParameter parameter) {
        PathVariable variable = parameter.getParameterAnnotation(PathVariable.class);
        if (variable != null && !variable.name().isEmpty()) {
            return variable.name();
        }
        if (variable != null && !variable.value().isEmpty()) {
            return variable.value();
        }
        // As Spring's own argument resolvers do: the name of an unnamed @PathVariable is the parameter's.
        parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        return parameter.getParameterName();
    }
}
