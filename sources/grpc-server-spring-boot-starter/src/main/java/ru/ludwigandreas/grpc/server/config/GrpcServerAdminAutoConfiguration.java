package ru.ludwigandreas.grpc.server.config;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.grpc.server.interceptor.OperationInterceptor;
import ru.ludwigandreas.grpc.server.properties.GrpcServerProperties;
import ru.ludwigandreas.security.admin.AdminApprovalPolicy;
import ru.ludwigandreas.security.admin.AdminCallerPolicy;
import ru.ludwigandreas.security.operation.OperationEvaluator;
import ru.ludwigandreas.security.operation.RequiresOperation;

/**
 * {@code @RequiresOperation} on gRPC methods, when the platform's security starter is on the classpath.
 *
 * <p>Registered whether or not the security starter or the administrative surface is configured, on purpose: it is
 * what notices an annotated method with nothing to honour it, and fails startup naming the method. Left out, the
 * method would serve anyone authenticated.
 */
@AutoConfiguration(afterName = {"ru.ludwigandreas.security.config.OperationAutoConfiguration",
        "ru.ludwigandreas.security.config.AdminAutoConfiguration"})
@ConditionalOnClass({RequiresOperation.class, OperationEvaluator.class})
@ConditionalOnProperty(prefix = GrpcServerProperties.PREFIX, name = "enabled", matchIfMissing = true)
public class GrpcServerAdminAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OperationInterceptor ludwigGrpcOperationInterceptor(
            ListableBeanFactory beans, ObjectProvider<OperationEvaluator> evaluator,
            ObjectProvider<AdminCallerPolicy> callers, ObjectProvider<AdminApprovalPolicy> approval) {
        return new OperationInterceptor(beans, evaluator, callers, approval);
    }
}
