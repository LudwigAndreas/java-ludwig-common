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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.grpc.core.context.ContextRestoringServerInterceptor;
import ru.ludwigandreas.grpc.core.metadata.GrpcMetadataKeys;
import ru.ludwigandreas.grpc.server.LudwigGrpcService;
import ru.ludwigandreas.grpc.server.error.GrpcServerConfigurationException;
import ru.ludwigandreas.grpc.server.error.GrpcServerMessages;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminAccessEvaluator;
import ru.ludwigandreas.security.admin.AdminApprovalPolicy;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.security.admin.AdminCallerPolicy;
import ru.ludwigandreas.security.principal.LudwigAuthentication;

/**
 * {@code @AdminAccess} on a method of a {@link LudwigGrpcService}: the caller is admitted and authorized by the same
 * caller policy and evaluator as the HTTP surface, and the handler runs with the administrative invocation bound.
 *
 * <p>Read reflectively once every singleton exists, as {@code @Idempotent} is: gRPC dispatches to the object
 * {@code bindService()} captured, so nothing else would ever see the annotation. A method naming an operation the
 * catalog lacks, one that requires four-eyes approval (HTTP-only), or any annotated method with no administrative
 * surface configured fails startup - the annotation is never silently ignored.
 *
 * <p>Inside the context bridge, so the caller's security context is on the thread; and the invocation is re-bound
 * around each callback for the same reason the bridge restores everything else per callback.
 */
public final class AdminAccessInterceptor implements BuiltInServerInterceptor, SmartInitializingSingleton {

    private final ListableBeanFactory beans;
    private final ObjectProvider<AdminOperationCatalog> catalog;
    private final ObjectProvider<AdminAccessEvaluator> evaluator;
    private final ObjectProvider<AdminCallerPolicy> callers;
    private final ObjectProvider<AdminApprovalPolicy> approval;
    private final Map<String, Method> annotated = new LinkedHashMap<>();
    private final Map<String, AdminOperation> operations = new LinkedHashMap<>();

    /**
     * Creates the interceptor.
     *
     * @param beans     where the stereotyped services are found
     * @param catalog   the declared operations, when the administrative surface is configured
     * @param evaluator the grant and reason check
     * @param callers   who may use the administrative surface at all
     * @param approval  which operations require four-eyes approval
     */
    public AdminAccessInterceptor(ListableBeanFactory beans, ObjectProvider<AdminOperationCatalog> catalog,
                                  ObjectProvider<AdminAccessEvaluator> evaluator,
                                  ObjectProvider<AdminCallerPolicy> callers,
                                  ObjectProvider<AdminApprovalPolicy> approval) {
        this.beans = beans;
        this.catalog = catalog;
        this.evaluator = evaluator;
        this.callers = callers;
        this.approval = approval;
    }

    @Override
    public Stage stage() {
        return Stage.ADMIN;
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
        AdminOperationCatalog operationCatalog = catalog.getIfAvailable();
        if (operationCatalog == null || evaluator.getIfAvailable() == null || callers.getIfAvailable() == null) {
            throw new GrpcServerConfigurationException(
                    GrpcServerMessages.startup("admin-surface-missing", annotated.keySet()));
        }
        AdminApprovalPolicy policy = approval.getIfAvailable();
        annotated.forEach((fullMethodName, method) -> {
            String id = AnnotatedElementUtils.findMergedAnnotation(method, AdminAccess.class).value();
            AdminOperation operation = operationCatalog.find(id).orElseThrow(() -> new GrpcServerConfigurationException(
                    GrpcServerMessages.startup("admin-operation-unknown", fullMethodName, id)));
            if (policy != null && policy.requires(id)) {
                throw new GrpcServerConfigurationException(
                        GrpcServerMessages.startup("admin-approval-unsupported", fullMethodName, id));
            }
            operations.put(fullMethodName, operation);
        });
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        String fullMethodName = call.getMethodDescriptor().getFullMethodName();
        AdminOperation operation = operations.get(fullMethodName);
        if (operation == null) {
            return next.startCall(call, headers);
        }
        Method method = annotated.get(fullMethodName);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        LudwigAuthentication caller = callers.getObject().admit(authentication, method, fullMethodName);
        AdminInvocation.Scope authorized = evaluator.getObject().authorize(authentication, caller.getPrincipal(),
                operation, headers.get(GrpcMetadataKeys.ADMIN_REASON), method);
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
                    && AnnotatedElementUtils.hasAnnotation(method, AdminAccess.class)) {
                annotated.put(definition.getMethodDescriptor().getFullMethodName(), method);
                return;
            }
        }
    }
}
