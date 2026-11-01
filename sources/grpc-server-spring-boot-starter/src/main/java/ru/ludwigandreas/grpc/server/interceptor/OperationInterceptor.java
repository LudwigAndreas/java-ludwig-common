package ru.ludwigandreas.grpc.server.interceptor;

import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerMethodDefinition;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.grpc.core.context.ContextRestoringServerInterceptor;
import ru.ludwigandreas.grpc.core.metadata.GrpcMetadataKeys;
import ru.ludwigandreas.grpc.server.LudwigGrpcService;
import ru.ludwigandreas.grpc.server.error.GrpcServerConfigurationException;
import ru.ludwigandreas.grpc.server.error.GrpcServerMessages;
import ru.ludwigandreas.security.admin.AdminApprovalPolicy;
import ru.ludwigandreas.security.admin.AdminCallerPolicy;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationEvaluator;
import ru.ludwigandreas.security.operation.RequiresOperation;
import ru.ludwigandreas.security.operation.Surface;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;

/**
 * {@code @RequiresOperation} on a method of a {@link LudwigGrpcService}, evaluated by the same evaluator as HTTP: the
 * caller must hold the operation, and a {@code PRIVILEGED} one needs {@code operation-reason} metadata and a direct
 * credential. An {@code ADMIN} operation is also admitted by the administrative caller policy and runs with the
 * administrative invocation bound, as on the HTTP surface.
 *
 * <p>Here rather than through method security because gRPC dispatches to the object {@code bindService()} captured -
 * the target, never its proxy - so nothing else would ever see the annotation. Read reflectively once every singleton
 * exists, as {@code @Idempotent} is. A method naming an uncatalogued operation, an administrative one that requires
 * four-eyes approval (HTTP-only) or one with no administrative surface configured, or any annotated method with no
 * operation catalog, fails startup - the annotation is never silently ignored.
 *
 * <p>Inside the context bridge, so the caller's security context is on the thread; and an administrative invocation
 * is re-bound around each callback for the same reason the bridge restores everything else per callback.
 */
public final class OperationInterceptor implements BuiltInServerInterceptor, SmartInitializingSingleton {

    private final ListableBeanFactory beans;
    private final ObjectProvider<OperationEvaluator> evaluator;
    private final ObjectProvider<AdminCallerPolicy> callers;
    private final ObjectProvider<AdminApprovalPolicy> approval;
    private final Map<String, Method> annotated = new LinkedHashMap<>();
    private final Map<String, Operation> operations = new LinkedHashMap<>();

    /**
     * Creates the interceptor.
     *
     * @param beans     where the stereotyped services are found
     * @param evaluator the hold, reason and credential check, when the security starter is on
     * @param callers   who may use the administrative surface at all, when it is configured
     * @param approval  which operations require four-eyes approval
     */
    public OperationInterceptor(ListableBeanFactory beans, ObjectProvider<OperationEvaluator> evaluator,
                                ObjectProvider<AdminCallerPolicy> callers,
                                ObjectProvider<AdminApprovalPolicy> approval) {
        this.beans = beans;
        this.evaluator = evaluator;
        this.callers = callers;
        this.approval = approval;
    }

    @Override
    public Stage stage() {
        return Stage.OPERATION;
    }

    /**
     * Finds the annotated methods, and refuses to start when one cannot be honoured.
     */
    @Override
    public void afterSingletonsInstantiated() {
        for (Object bean : beans.getBeansWithAnnotation(LudwigGrpcService.class).values()) {
            if (bean instanceof BindableService service) {
                Class<?> type = AopUtils.getTargetClass(bean);
                service.bindService().getMethods().forEach(method -> register(type, method));
            }
        }
        if (annotated.isEmpty()) {
            return;
        }
        OperationEvaluator operationEvaluator = evaluator.getIfAvailable();
        if (operationEvaluator == null) {
            throw new GrpcServerConfigurationException(
                    GrpcServerMessages.startup("operation-catalog-missing", annotated.keySet()));
        }
        AdminApprovalPolicy policy = approval.getIfAvailable();
        annotated.forEach((fullMethodName, method) -> {
            String id = AnnotatedElementUtils.findMergedAnnotation(method, RequiresOperation.class).value();
            Operation operation = operationEvaluator.authority().grants().catalog().find(id).orElseThrow(() ->
                    new GrpcServerConfigurationException(
                            GrpcServerMessages.startup("operation-unknown", fullMethodName, id)));
            if (operation.surface() == Surface.ADMIN) {
                if (callers.getIfAvailable() == null) {
                    throw new GrpcServerConfigurationException(
                            GrpcServerMessages.startup("admin-surface-missing", fullMethodName, id));
                }
                if (policy != null && policy.requires(id)) {
                    throw new GrpcServerConfigurationException(
                            GrpcServerMessages.startup("admin-approval-unsupported", fullMethodName, id));
                }
            }
            operations.put(fullMethodName, operation);
        });
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        String fullMethodName = call.getMethodDescriptor().getFullMethodName();
        Operation operation = operations.get(fullMethodName);
        if (operation == null) {
            return next.startCall(call, headers);
        }
        Method method = annotated.get(fullMethodName);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String reason = headers.get(GrpcMetadataKeys.OPERATION_REASON);
        if (operation.surface() == Surface.BUSINESS) {
            if (authentication == null || !(authentication.getPrincipal() instanceof LudwigPrincipal principal)) {
                throw new AccessDeniedException("Operation " + operation.id() + " requires an authenticated caller");
            }
            evaluator.getObject().authorize(authentication, principal, operation, reason, method);
            return next.startCall(call, headers);
        }
        LudwigAuthentication caller = callers.getObject().admit(authentication, method, fullMethodName);
        AdminInvocation.Scope authorized = AdminInvocation.open(
                evaluator.getObject().authorize(authentication, caller.getPrincipal(), operation, reason, method));
        // Bound again around startCall and every callback below, on whichever thread delivers it.
        authorized.close();
        return ContextRestoringServerInterceptor.interceptCall(Context.current(),
                List.of(context -> authorized.reopen()::close), call, headers, next);
    }

    private void register(Class<?> type, ServerMethodDefinition<?, ?> definition) {
        String bareMethodName = definition.getMethodDescriptor().getBareMethodName();
        if (bareMethodName == null || bareMethodName.isEmpty()) {
            return;
        }
        // Store in the contract is store in the generated base class.
        String javaName = Character.toLowerCase(bareMethodName.charAt(0)) + bareMethodName.substring(1);
        for (Method method : type.getMethods()) {
            if (method.getName().equals(javaName)
                    && AnnotatedElementUtils.hasAnnotation(method, RequiresOperation.class)) {
                annotated.put(definition.getMethodDescriptor().getFullMethodName(), method);
                return;
            }
        }
    }
}
