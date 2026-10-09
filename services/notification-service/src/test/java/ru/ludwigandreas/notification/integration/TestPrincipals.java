package ru.ludwigandreas.notification.integration;

import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * Callers the integration tests run requests as - this service's own vocabulary.
 *
 * <p>The mechanism lives in {@link TestPrincipalBuilder}, which also records why the authentication is
 * injected rather than minted as a real JWT. What stays here is this service's own: tenants, support
 * agents, a peer service in the mesh, and the role names that encode what each may do.
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
        return TestPrincipalBuilder.user(ADMIN_SUBJECT)
                .displayName("An Admin")
                .roles("ROLE_NOTIFICATION_ADMIN")
                .postProcessor();
    }

    /** Sees only its own tenant's delivery history - the data scope, not the endpoint gate. */
    static RequestPostProcessor support(String tenantId) {
        return TestPrincipalBuilder.user(SUPPORT_SUBJECT)
                .displayName("Support")
                .tenant(tenantId)
                .roles("ROLE_NOTIFICATION_SUPPORT")
                .postProcessor();
    }

    /**
     * Another service in the mesh, as {@code JwtPrincipalConverter} would build it from a workload
     * identity - authorized on its role, never on a shared secret.
     */
    static RequestPostProcessor peerService(String tenantId) {
        return TestPrincipalBuilder.service(PEER_SERVICE)
                .displayName("orders")
                .tenant(tenantId)
                .roles("ROLE_NOTIFICATION_SENDER")
                .postProcessor();
    }

    /**
     * An ordinary person, holding no notification role at all, reading their own inbox.
     *
     * <p>Deliberately role-free. Reading one's own notifications is not a privilege anybody grants -
     * every authenticated person has an inbox - so the inbox endpoints gate on
     * {@code isAuthenticated()} and the row-level rule is the owner predicate. A test that used
     * {@link #admin()} here would pass just as well against an endpoint that had been gated on a role
     * by mistake, and would prove nothing about the one caller the API is actually for.
     *
     * @param subject the person's subject, which is also the inbox owner they will see
     */
    static RequestPostProcessor recipient(String subject) {
        return TestPrincipalBuilder.user(subject)
                .displayName("A Recipient")
                .tenant(TENANT)
                .postProcessor();
    }

    /** Authenticated, but holding no notification role at all. */
    static RequestPostProcessor outsider() {
        return TestPrincipalBuilder.user("outsider")
                .displayName("Outsider")
                .roles("ROLE_SOMETHING_ELSE")
                .postProcessor();
    }
}
