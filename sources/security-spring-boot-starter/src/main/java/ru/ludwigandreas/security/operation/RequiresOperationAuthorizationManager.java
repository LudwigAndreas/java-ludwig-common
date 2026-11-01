package ru.ludwigandreas.security.operation;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * Enforces {@link RequiresOperation} on a bean method through method security, so that it holds through proxies and
 * on every caller of an application service, not only on its controller.
 *
 * <p>A {@code BUSINESS} operation is evaluated here. An {@code ADMIN} one is evaluated by its surface - the
 * administrative surface guard or the gRPC operation interceptor - which binds the authorized invocation; here it is
 * only confirmed that exactly this operation is bound, so a call that did not come through the surface is refused and
 * one that did is not evaluated twice.
 */
public final class RequiresOperationAuthorizationManager implements AuthorizationManager<MethodInvocation> {

    private final ObjectProvider<OperationEvaluator> evaluator;

    /**
     * Creates the manager.
     *
     * @param evaluator the evaluation every surface shares, resolved on first use
     */
    public RequiresOperationAuthorizationManager(ObjectProvider<OperationEvaluator> evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    @SuppressWarnings("deprecation")
    public AuthorizationDecision check(Supplier<Authentication> authentication, MethodInvocation invocation) {
        Method method = invocation.getThis() == null ? invocation.getMethod()
                : AopUtils.getMostSpecificMethod(invocation.getMethod(), AopUtils.getTargetClass(invocation.getThis()));
        RequiresOperation declared = AnnotatedElementUtils.findMergedAnnotation(method, RequiresOperation.class);
        if (declared == null) {
            declared = AnnotatedElementUtils.findMergedAnnotation(invocation.getMethod(), RequiresOperation.class);
        }
        if (declared == null) {
            return new AuthorizationDecision(false);
        }
        OperationEvaluator operations = evaluator.getObject();
        Operation operation = operations.authority().require(declared.value());
        if (operation.surface() == Surface.ADMIN) {
            boolean bound = AdminInvocation.current()
                    .map(current -> current.operation().id().equals(operation.id()))
                    .orElse(false);
            return new AuthorizationDecision(bound);
        }
        Authentication caller = authentication.get();
        if (caller == null || !(caller.getPrincipal() instanceof LudwigPrincipal principal)) {
            throw new AccessDeniedException("Operation " + operation.id() + " requires an authenticated caller");
        }
        operations.authorize(caller, principal, operation, statedReason(), invocation);
        return new AuthorizationDecision(true);
    }

    /** The reason header of the HTTP request this thread is serving, when there is one. */
    private static String statedReason() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            HttpServletRequest request = servlet.getRequest();
            return request.getHeader(OperationEvaluator.REASON_HEADER);
        }
        return null;
    }
}
