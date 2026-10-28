package ru.ludwigandreas.security.admin;

import java.util.function.Supplier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.core.Authentication;
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

    /**
     * Creates the evaluator.
     *
     * @param grants    the validated grants
     * @param publisher where denials are published
     */
    public AdminAccessEvaluator(AdminGrants grants, AuthorizationEventPublisher publisher) {
        this.grants = grants;
        this.publisher = publisher;
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
        Supplier<Authentication> supplier = () -> authentication;
        publisher.publishAuthorizationEvent(supplier, handler, new AuthorizationDecision(false));
        throw new AccessDeniedException("Caller does not hold administrative operation " + operationId);
    }
}
