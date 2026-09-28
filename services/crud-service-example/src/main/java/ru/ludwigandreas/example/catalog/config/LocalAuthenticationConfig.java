package ru.ludwigandreas.example.catalog.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * Lets the service be driven with {@code curl} on a laptop, with no identity provider, no session layer
 * and no Kafka - by taking the caller from request headers.
 *
 * <p><b>This is a backdoor, and it is gated twice on purpose:</b> the {@code local} profile must be
 * active <em>and</em> {@code ludwig.security.local-authentication.enabled} must be explicitly set. Either
 * gate alone is one typo away from a production deployment that accepts
 * {@code X-Local-Roles: ROLE_CATALOG_ADMIN} from anyone. Two independent gates, neither of which is a
 * default, is the least this deserves; a real deployment should also make sure the {@code local} profile
 * cannot be selected in its environment at all.
 *
 * <p>What it does <em>not</em> do is bypass authorization. The principal it builds goes through the same
 * {@code @PreAuthorize} checks and the same data scopes as one minted from a real token, which is what
 * makes the walkthrough in the README worth anything: change the roles header and watch the same
 * endpoint answer differently.
 */
@Slf4j
@Profile("local")
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ludwig.security.local-authentication", name = "enabled",
        havingValue = "true")
public class LocalAuthenticationConfig {

    private static final String SUBJECT_HEADER = "X-Local-Subject";
    private static final String ROLES_HEADER = "X-Local-Roles";
    private static final String TYPE_HEADER = "X-Local-Type";

    /**
     * Replaces the decoder Spring Boot builds from {@code issuer-uri}, which performs OIDC discovery when
     * the bean is created - so without this the service could not start while the provider is
     * unreachable, which on a laptop is always. It rejects every token rather than accepting any: the
     * local caller comes from headers, and a decoder that waved tokens through would hide real
     * misconfiguration.
     */
    @Bean
    public JwtDecoder localJwtDecoder() {
        return token -> {
            throw new JwtException("Local profile issues no tokens - use the X-Local-* headers");
        };
    }

    @Bean
    public OncePerRequestFilter localAuthenticationFilter() {
        log.warn("LOCAL AUTHENTICATION IS ACTIVE: callers are taken from the {} / {} headers. "
                + "This must never run outside a developer machine.", SUBJECT_HEADER, ROLES_HEADER);
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                String subject = request.getHeader(SUBJECT_HEADER);
                if (subject != null && !subject.isBlank()) {
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new LudwigAuthentication(principal(request, subject)));
                    SecurityContextHolder.setContext(context);
                }
                try {
                    chain.doFilter(request, response);
                } finally {
                    // Request threads are pooled: a leaked context would attribute the next caller's
                    // actions to this one.
                    SecurityContextHolder.clearContext();
                }
            }
        };
    }

    private LudwigPrincipal principal(HttpServletRequest request, String subject) {
        String rawRoles = request.getHeader(ROLES_HEADER);
        Set<String> roles = rawRoles == null || rawRoles.isBlank()
                ? Set.of()
                : Arrays.stream(rawRoles.split(","))
                        .map(String::trim)
                        .filter(role -> !role.isEmpty())
                        .collect(Collectors.toSet());

        String rawType = request.getHeader(TYPE_HEADER);
        PrincipalType type = rawType == null || rawType.isBlank()
                ? PrincipalType.USER
                : PrincipalType.valueOf(rawType.trim().toUpperCase(java.util.Locale.ROOT));

        return LudwigPrincipal.builder()
                .subject(subject)
                .type(type)
                .displayName(subject)
                .roles(roles)
                .build();
    }
}
