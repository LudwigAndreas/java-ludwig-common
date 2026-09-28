package ru.ludwigandreas.testsupport.security;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * Mints a {@link LudwigPrincipal} and wraps it in an authentication a {@code MockMvc} request can carry.
 *
 * <h2>Why the authentication is injected rather than a real JWT minted</h2>
 *
 * <p>This reasoning is shared by both services' fixtures and is the reason they were written this way.
 * What an integration test exercises is authorization - which endpoints a role reaches and which rows a
 * scope returns - and that is decided entirely from the {@code LudwigPrincipal}. Signing a token would
 * add a key pair and an issuer stub to the test without exercising one extra line of the policy. Token
 * validation is the security module's own concern and has its own tests there.
 *
 * <h2>Why only the builder is shared, and not the constants</h2>
 *
 * <p>The two services' {@code TestPrincipals} classes looked near-identical and are not. They share a
 * shape but diverge entirely in vocabulary: {@code crud-service-example} speaks of editors, watchers,
 * a partner code and {@code ROLE_CATALOG_ADMIN}; {@code notification-service} speaks of support agents,
 * tenants and a peer service's SPIFFE id. Extracting either one wholesale would have given the other
 * service a fixture full of names from somebody else's domain - readable to nobody and wrong in
 * assertions.
 *
 * <p>So what moved here is the mechanism: subject, type, roles, tenant, attributes, and the wrapping.
 * Each service keeps a small constants class naming its own roles and subjects, which is where a
 * reader looking for "what callers does this service have" should find them. This class holds no
 * domain vocabulary and must not acquire any.
 *
 * <h2>Roles are normalised for you</h2>
 *
 * <p>{@code LudwigPrincipal} prefixes bare role names with {@code ROLE_} in its own constructor, so
 * {@code roles("CATALOG_ADMIN")} and {@code roles("ROLE_CATALOG_ADMIN")} are the same principal. That
 * is deliberate in the record and not re-implemented here.
 *
 * <h2>Use</h2>
 *
 * <pre>{@code
 * static RequestPostProcessor admin() {
 *     return TestPrincipalBuilder.user(ADMIN_SUBJECT).roles(ROLE_ADMIN).postProcessor();
 * }
 *
 * static RequestPostProcessor support(String tenantId) {
 *     return TestPrincipalBuilder.user(SUPPORT_SUBJECT)
 *             .tenant(tenantId)
 *             .roles(ROLE_SUPPORT)
 *             .postProcessor();
 * }
 * }</pre>
 */
public final class TestPrincipalBuilder {

    private final String subject;
    private final PrincipalType type;
    private final Set<String> roles = new LinkedHashSet<>();
    private final Set<String> permissions = new LinkedHashSet<>();
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private String displayName;
    private String tenantId;

    private TestPrincipalBuilder(String subject, PrincipalType type) {
        this.subject = subject;
        this.type = type;
        this.displayName = subject;
    }

    /**
     * A human caller, as a JWT from the identity provider would produce.
     *
     * @param subject the OIDC {@code sub}
     * @return a builder
     */
    public static TestPrincipalBuilder user(String subject) {
        return new TestPrincipalBuilder(subject, PrincipalType.USER);
    }

    /**
     * An external partner, as {@code MutualTlsAuthenticationFilter} would build it from a verified
     * client certificate.
     *
     * <p>The subject is the partner's stable code, which is also the value a {@code PARTNER} data scope
     * matches against - not a username.
     *
     * @param partnerCode the partner's stable code
     * @return a builder
     */
    public static TestPrincipalBuilder partner(String partnerCode) {
        return new TestPrincipalBuilder(partnerCode, PrincipalType.PARTNER);
    }

    /**
     * Another service in the mesh, as {@code JwtPrincipalConverter} would build it from a workload
     * identity - authorized on its role, never on a shared secret.
     *
     * @param workloadId the workload identity, e.g. a SPIFFE id
     * @return a builder
     */
    public static TestPrincipalBuilder service(String workloadId) {
        return new TestPrincipalBuilder(workloadId, PrincipalType.SERVICE);
    }

    /**
     * Sets the label used in logs and UIs. Defaults to the subject.
     *
     * <p>Never consulted in an authorization decision, so a test only sets it when an assertion reads it.
     *
     * @param newDisplayName the label
     * @return this builder
     */
    public TestPrincipalBuilder displayName(String newDisplayName) {
        this.displayName = newDisplayName;
        return this;
    }

    /**
     * Sets the owning organization, for a multi-tenant deployment.
     *
     * @param newTenantId the tenant id
     * @return this builder
     */
    public TestPrincipalBuilder tenant(String newTenantId) {
        this.tenantId = newTenantId;
        return this;
    }

    /**
     * Adds roles. Bare names are prefixed with {@code ROLE_} by the principal itself.
     *
     * @param newRoles the roles to add
     * @return this builder
     */
    public TestPrincipalBuilder roles(String... newRoles) {
        this.roles.addAll(Arrays.asList(newRoles));
        return this;
    }

    /**
     * Adds fine-grained permissions, for the cases where roles are too blunt.
     *
     * @param newPermissions the permissions to add
     * @return this builder
     */
    public TestPrincipalBuilder permissions(String... newPermissions) {
        this.permissions.addAll(Arrays.asList(newPermissions));
        return this;
    }

    /**
     * Adds an attribute a {@code DataScopeProvider} may key on - a branch, a region, a contract id.
     *
     * @param name  the attribute name
     * @param value the attribute value
     * @return this builder
     */
    public TestPrincipalBuilder attribute(String name, String value) {
        this.attributes.put(name, value);
        return this;
    }

    /**
     * Builds the principal.
     *
     * @return the principal
     */
    public LudwigPrincipal principal() {
        return LudwigPrincipal.builder()
                .subject(subject)
                .type(type)
                .displayName(displayName)
                .tenantId(tenantId)
                .roles(roles)
                .permissions(permissions)
                .attributes(attributes)
                .build();
    }

    /**
     * Builds the authentication that carries the principal.
     *
     * @return the authentication
     */
    public LudwigAuthentication authentication() {
        return new LudwigAuthentication(principal());
    }

    /**
     * Builds a post-processor that runs a {@code MockMvc} request as this caller.
     *
     * @return the post-processor, for {@code .with(...)} on a request builder
     */
    public RequestPostProcessor postProcessor() {
        return SecurityMockMvcRequestPostProcessors.authentication(authentication());
    }
}
