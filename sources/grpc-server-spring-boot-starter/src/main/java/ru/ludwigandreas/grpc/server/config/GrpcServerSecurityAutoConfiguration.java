package ru.ludwigandreas.grpc.server.config;

import ru.ludwigandreas.grpc.server.server.GrpcServerConfigurationValidator;
import ru.ludwigandreas.grpc.server.properties.GrpcServerProperties;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.util.ClassUtils;
import ru.ludwigandreas.grpc.server.interceptor.AuthenticationInterceptor;
import ru.ludwigandreas.security.web.SecurityHeaders;

/**
 * Authentication of gRPC callers, from the beans the HTTP resource server already has.
 *
 * <p>There is deliberately no fallback here. With no {@link JwtDecoder} bean this class contributes
 * nothing, the chain has no authentication stage, and {@link GrpcServerConfigurationValidator}
 * refuses to start the server - unless {@code ludwig.grpc.server.security.enabled=false} says the
 * absence is intended. A server that quietly serves unauthenticated because a dependency was
 * dropped is the failure that arrangement prevents.
 */
@AutoConfiguration(afterName = {
    "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration",
    "ru.ludwigandreas.security.config.ResourceServerAutoConfiguration"})
@ConditionalOnClass({JwtDecoder.class, JwtAuthenticationConverter.class, SecurityContextHolder.class})
@ConditionalOnProperty(
        name = {GrpcServerProperties.PREFIX + ".enabled", GrpcServerProperties.PREFIX + ".security.enabled"},
        matchIfMissing = true)
@EnableConfigurationProperties(GrpcServerProperties.class)
public class GrpcServerSecurityAutoConfiguration {

    private static final String SECURITY_HEADERS = "ru.ludwigandreas.security.web.SecurityHeaders";

    @Bean
    @ConditionalOnBean(JwtDecoder.class)
    @ConditionalOnMissingBean
    public AuthenticationInterceptor ludwigGrpcAuthenticationInterceptor(
            JwtDecoder decoder, ObjectProvider<Converter<Jwt, AbstractAuthenticationToken>> converter,
            GrpcServerProperties properties) {
        List<String> permitted = new ArrayList<>(properties.getSecurity().getPermitUnauthenticated());
        permitted.add(HealthGrpc.SERVICE_NAME + "/*");
        if (properties.getReflection().isEnabled()) {
            permitted.add(ServerReflectionGrpc.SERVICE_NAME + "/*");
        }
        return new AuthenticationInterceptor(decoder,
                converter.getIfUnique(JwtAuthenticationConverter::new), permitted, forbiddenHeaders());
    }

    /**
     * The identity headers the HTTP chain strips, so both transports refuse the same forgeries.
     * Empty without the platform's security starter: nothing downstream trusts them then.
     */
    private static List<String> forbiddenHeaders() {
        return ClassUtils.isPresent(SECURITY_HEADERS, GrpcServerSecurityAutoConfiguration.class.getClassLoader())
                ? PlatformSecurity.forbiddenHeaders()
                : List.of();
    }

    private static final class PlatformSecurity {

        private PlatformSecurity() {
        }

        static List<String> forbiddenHeaders() {
            return SecurityHeaders.CLIENT_FORBIDDEN;
        }
    }
}
