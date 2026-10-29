package ru.ludwigandreas.grpc.server.config;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.grpc.server.interceptor.AdminAccessInterceptor;
import ru.ludwigandreas.grpc.server.properties.GrpcServerProperties;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminAccessEvaluator;
import ru.ludwigandreas.security.admin.AdminApprovalPolicy;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.security.admin.AdminCallerPolicy;

/**
 * {@code @AdminAccess} on gRPC methods, when the platform's security starter is on the classpath.
 *
 * <p>Registered whether or not the administrative surface is configured, on purpose: it is what notices an annotated
 * method with nothing to honour it, and fails startup naming the method. Left out, the method would serve anyone
 * authenticated.
 */
@AutoConfiguration(afterName = "ru.ludwigandreas.security.config.AdminAutoConfiguration")
@ConditionalOnClass({AdminAccess.class, AdminAccessEvaluator.class})
@ConditionalOnProperty(prefix = GrpcServerProperties.PREFIX, name = "enabled", matchIfMissing = true)
public class GrpcServerAdminAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public AdminAccessInterceptor ludwigGrpcAdminAccessInterceptor(
            ListableBeanFactory beans, ObjectProvider<AdminOperationCatalog> catalog,
            ObjectProvider<AdminAccessEvaluator> evaluator, ObjectProvider<AdminCallerPolicy> callers,
            ObjectProvider<AdminApprovalPolicy> approval) {
        return new AdminAccessInterceptor(beans, catalog, evaluator, callers, approval);
    }
}
