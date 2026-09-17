package ru.ludwigandreas.notification.integration;

import java.util.Set;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * Callers the integration tests run requests as.
 *
 * <p>The authentication is injected rather than minted as a real JWT on purpose. What is under test
 * is authorization - which endpoints a role reaches and which rows a tenant scope returns - and that
 * is decided entirely from the {@code LudwigPrincipal}. Signing a token would add a key pair and an
 * issuer stub without exercising one extra line of the policy; token validation belongs to the
 * security module's own tests.
 */
final class TestPrincipals {

    static final String ADMIN_SUBJECT = "admin-subject";
    static final String SUPPORT_SUBJECT = "support-subject";
    static final String TENANT = "acme";
    static final String OTHER_TENANT = "globex";
    static final String PEER_SERVICE = "spiffe://mesh/ns/orders/sa/orders";

    private TestPrincipals() {
    }

    /** Reads and writes everything, in every tenant. */
    static RequestPostProcessor admin() {
        return authentication(LudwigPrincipal.builder()
                .subject(ADMIN_SUBJECT)
                .type(PrincipalType.USER)
                .displayName("An Admin")
                .roles(Set.of("ROLE_NOTIFICATION_ADMIN"))
                .build());
    }

    /** Sees only its own tenant's delivery history - the data scope, not the endpoint gate. */
    static RequestPostProcessor support(String tenantId) {
        return authentication(LudwigPrincipal.builder()
                .subject(SUPPORT_SUBJECT)
                .type(PrincipalType.USER)
                .displayName("Support")
                .tenantId(tenantId)
                .roles(Set.of("ROLE_NOTIFICATION_SUPPORT"))
                .build());
    }

    /**
     * Another service in the mesh, as {@code JwtPrincipalConverter} would build it from a workload
     * identity - authorized on its role, never on a shared secret.
     */
    static RequestPostProcessor peerService(String tenantId) {
        return authentication(LudwigPrincipal.builder()
                .subject(PEER_SERVICE)
                .type(PrincipalType.SERVICE)
                .displayName("orders")
                .tenantId(tenantId)
                .roles(Set.of("ROLE_NOTIFICATION_SENDER"))
                .build());
    }

    /** Authenticated, but holding no notification role at all. */
    static RequestPostProcessor outsider() {
        return authentication(LudwigPrincipal.builder()
                .subject("outsider")
                .type(PrincipalType.USER)
                .displayName("Outsider")
                .roles(Set.of("ROLE_SOMETHING_ELSE"))
                .build());
    }

    private static RequestPostProcessor authentication(LudwigPrincipal principal) {
        return SecurityMockMvcRequestPostProcessors.authentication(new LudwigAuthentication(principal));
    }
}
