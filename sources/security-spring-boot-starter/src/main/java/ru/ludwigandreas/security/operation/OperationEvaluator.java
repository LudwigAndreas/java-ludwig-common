package ru.ludwigandreas.security.operation;

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
import ru.ludwigandreas.security.admin.AdminObject;
import ru.ludwigandreas.security.admin.AdminObjectNotFoundException;
import ru.ludwigandreas.security.data.DataScope;
import ru.ludwigandreas.security.data.ScopeDimension;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.web.PatCredentialNotPermittedException;

/**
 * The one evaluation of a call to an operation, for every surface and transport.
 *
 * <ol>
 *   <li>The caller must hold the operation; otherwise the denial is published - audited and metered as every other
 *       authorization denial - and {@link AccessDeniedException} thrown.</li>
 *   <li>A {@code PRIVILEGED} operation refuses a personal access token unless the deployment allows it, and needs a
 *       stated reason of at most {@link #MAX_REASON_LENGTH} characters, else {@link ReasonRequiredException}. Both are
 *       checked after the grant, so that neither tells a caller who could not perform the operation anything.</li>
 * </ol>
 *
 * <p>Surface rules beyond these - partners, acting parties, approval - are the surface's own, applied before it calls
 * this.
 */
public class OperationEvaluator {

    /** The header carrying the caller's reason. Mandatory for a {@code PRIVILEGED} operation. */
    public static final String REASON_HEADER = "Operation-Reason";

    /** The longest reason accepted, after trimming. */
    public static final int MAX_REASON_LENGTH = 500;

    private final OperationAuthority authority;
    private final AuthorizationEventPublisher publisher;
    private final boolean allowPersonalAccessTokens;

    /**
     * Creates the evaluator.
     *
     * @param authority                 who holds what
     * @param publisher                 where denials are published
     * @param allowPersonalAccessTokens whether a PAT may perform a {@code PRIVILEGED} operation
     */
    public OperationEvaluator(OperationAuthority authority, AuthorizationEventPublisher publisher,
                              boolean allowPersonalAccessTokens) {
        this.authority = authority;
        this.publisher = publisher;
        this.allowPersonalAccessTokens = allowPersonalAccessTokens;
    }

    /**
     * Authorizes a call.
     *
     * @param authentication the caller's authentication
     * @param principal      the caller
     * @param operation      the operation the call performs
     * @param reason         the stated reason as sent, or null
     * @param target         what a denial names - the handler, the method invocation, or a gRPC service method
     * @return the authorization, for the surface to bind to the call
     */
    public Authorization authorize(Authentication authentication, LudwigPrincipal principal, Operation operation,
                                   String reason, Object target) {
        if (!authority.holds(principal, operation.id())) {
            refuse(authentication, target, "Caller does not hold operation " + operation.id());
        }
        String stated = reason == null ? "" : reason.trim();
        if (operation.sensitivity() == Sensitivity.PRIVILEGED) {
            if (!allowPersonalAccessTokens && authentication instanceof LudwigAuthentication ludwig
                    && ludwig.credential().isLongLived()) {
                throw new PatCredentialNotPermittedException(ludwig.credential().kind().name());
            }
            if (stated.isEmpty()) {
                throw new ReasonRequiredException();
            }
        }
        if (stated.length() > MAX_REASON_LENGTH) {
            throw new ReasonRequiredException();
        }
        return new Authorization(operation, principal, stated.isEmpty() ? null : stated);
    }

    /**
     * Refuses the call, publishing the denial so that it is audited and metered as every other one is.
     *
     * @param authentication the caller's authentication
     * @param target         what was refused
     * @param message        why, for the log; the caller is told only that access was denied
     * @throws AccessDeniedException always
     */
    public void refuse(Authentication authentication, Object target, String message) {
        publishDenial(authentication, target);
        throw new AccessDeniedException(message);
    }

    /**
     * Refuses the call unless the object its {@link AdminObject} parameter names is in the caller's scope.
     *
     * <p>Applies only where the caller's scope confines objects: an unrestricted scope, and one whose restriction is on
     * another dimension, admit every named object. Out of scope is {@code 403}, or the area's not-found {@code 404} for
     * a {@code READ} operation so that existence is not disclosed; both are published as denials.
     *
     * @param authentication the caller's authentication
     * @param principal      the caller
     * @param handler        the handler being invoked
     * @param operation      the operation it declares
     * @param pathVariables  the request's URI template variables
     */
    public void authorizeObject(Authentication authentication, LudwigPrincipal principal, HandlerMethod handler,
                                Operation operation, Map<String, String> pathVariables) {
        DataScope scope = authority.scope(principal, operation);
        if (!OperationAuthority.confinesObjects(scope)) {
            return;
        }
        for (MethodParameter parameter : handler.getMethodParameters()) {
            AdminObject declared = parameter.getParameterAnnotation(AdminObject.class);
            if (declared == null) {
                continue;
            }
            String object = pathVariables.get(variableName(parameter));
            if (object != null && scope.admits(ScopeDimension.OBJECT, object)) {
                continue;
            }
            publishDenial(authentication, handler);
            if (operation.sensitivity() == Sensitivity.READ) {
                throw new AdminObjectNotFoundException(declared.notFound(), object);
            }
            throw new AccessDeniedException("Object '" + object + "' is outside the caller's scope");
        }
    }

    /**
     * Who holds what.
     *
     * @return the authority
     */
    public OperationAuthority authority() {
        return authority;
    }

    private void publishDenial(Authentication authentication, Object target) {
        Supplier<Authentication> supplier = () -> authentication;
        publisher.publishAuthorizationEvent(supplier, target, new AuthorizationDecision(false));
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
