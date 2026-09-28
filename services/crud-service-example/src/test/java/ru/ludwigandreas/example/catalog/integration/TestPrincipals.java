package ru.ludwigandreas.example.catalog.integration;

import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.testsupport.security.TestPrincipalBuilder;

/**
 * Callers the integration test runs requests as - this service's own vocabulary.
 *
 * <p>The mechanism lives in {@link TestPrincipalBuilder}, which also records why the authentication is
 * injected rather than minted as a real JWT. What stays here is the part that is this service's: which
 * callers a catalog has, what each of them is allowed to do, and the role names that encode it.
 *
 * <p>That division is deliberate. This class and {@code notification-service}'s namesake looked
 * near-identical and were not: they share a shape but no vocabulary, so extracting either one wholesale
 * would have given the other service a fixture full of names from somebody else's domain.
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
        return TestPrincipalBuilder.partner(PARTNER_CODE)
                .displayName("ACME GmbH")
                .roles("ROLE_CATALOG_PARTNER")
                .postProcessor();
    }

    /**
     * Holds no catalog-wide read role at all: everything this caller can see comes from the membership
     * scope granted by {@code watcherDataScopeProvider}.
     */
    static RequestPostProcessor watcher() {
        return user(WATCHER_SUBJECT, "ROLE_CATALOG_WATCHER");
    }

    private static RequestPostProcessor user(String subject, String role) {
        return TestPrincipalBuilder.user(subject).roles(role).postProcessor();
    }
}
