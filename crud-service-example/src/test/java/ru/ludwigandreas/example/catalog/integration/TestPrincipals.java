package ru.ludwigandreas.example.catalog.integration;

import java.util.Set;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * Callers the integration test runs requests as.
 *
 * <p>The authentication is injected rather than minted as a real JWT on purpose. What is under test here
 * is authorization - which endpoints a role reaches and which rows a scope returns - and that is decided
 * entirely from the {@code LudwigPrincipal}. Signing a token would only add a key pair and an issuer stub
 * to the test without exercising one extra line of the policy; token validation belongs to the security
 * module's own tests.
 */
public final class TestPrincipals {

    static final String ADMIN_SUBJECT = "admin-subject";
    static final String EDITOR_SUBJECT = "editor-subject";
    static final String OTHER_EDITOR_SUBJECT = "other-editor-subject";
    static final String PARTNER_CODE = "acme";
    static final String WATCHER_SUBJECT = "watcher-subject";

    private TestPrincipals() {
    }

    /** Reads and writes everything, and the only role allowed to delete. */
    static RequestPostProcessor admin() {
        return user(ADMIN_SUBJECT, "ROLE_CATALOG_ADMIN");
    }

    /** Reads the whole catalog, writes only what it created (policy: {@code write: OWN}). */
    static RequestPostProcessor editor() {
        return user(EDITOR_SUBJECT, "ROLE_CATALOG_EDITOR");
    }

    static RequestPostProcessor otherEditor() {
        return user(OTHER_EDITOR_SUBJECT, "ROLE_CATALOG_EDITOR");
    }

    /**
     * An external partner, as {@code MutualTlsAuthenticationFilter} would build it from a verified
     * client certificate: the subject is the partner's stable code, which is also the value its
     * {@code PARTNER} data scope matches against {@code product.supplier_partner_id}.
     */
    static RequestPostProcessor partner() {
        return authentication(LudwigPrincipal.builder()
                .subject(PARTNER_CODE)
                .type(PrincipalType.PARTNER)
                .displayName("ACME GmbH")
                .roles(Set.of("ROLE_CATALOG_PARTNER"))
                .build());
    }

    /**
     * Holds no catalog-wide read role at all: everything this caller can see comes from the membership
     * scope granted by {@code watcherDataScopeProvider}.
     */
    static RequestPostProcessor watcher() {
        return user(WATCHER_SUBJECT, "ROLE_CATALOG_WATCHER");
    }

    private static RequestPostProcessor user(String subject, String role) {
        return authentication(LudwigPrincipal.builder()
                .subject(subject)
                .type(PrincipalType.USER)
                .displayName(subject)
                .roles(Set.of(role))
                .build());
    }

    private static RequestPostProcessor authentication(LudwigPrincipal principal) {
        return SecurityMockMvcRequestPostProcessors.authentication(new LudwigAuthentication(principal));
    }
}
