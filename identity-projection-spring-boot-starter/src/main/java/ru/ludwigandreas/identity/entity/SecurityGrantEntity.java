package ru.ludwigandreas.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import ru.ludwigandreas.db.core.entity.AuditedEntity;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * One explicit data-scope grant: "this principal may act on this resource, for this action, restricted to
 * this dimension value".
 *
 * <p>The database counterpart to the role-to-scope policy in configuration, and it exists because the two
 * kinds of grant have genuinely different lifecycles. "Agents see their own cases" is a rule about the
 * system and belongs in a file that is reviewed and deployed. "Anna covers Berlin and Hamburg while Bob is
 * on leave" is data: it is created by a person, it is temporary, and it must not require a release.
 *
 * <p>The two are unioned at read time ({@code CompositeDataScopeProvider}), so a grant here can only ever
 * widen what the policy allows - never narrow it. Narrowing is done by removing roles.
 */
@Getter
@Setter
@Entity
@Table(name = "security_grant")
public class SecurityGrantEntity extends AuditedEntity<UUID> {

    /** The user's OIDC subject, the partner's code, or a workload id - matching {@link #principalType}. */
    @Column(name = "subject", nullable = false, length = 255)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 32)
    private PrincipalType principalType;

    /** The resource name a {@code DataScopeMapping} is registered under, e.g. {@code order}. */
    @Column(name = "resource_type", nullable = false, length = 128)
    private String resourceType;

    /** {@code read}, {@code write}, {@code delete} or a service-specific verb. */
    @Column(name = "action", nullable = false, length = 64)
    private String action;

    /**
     * {@code owner}, {@code tenant}, {@code partner} or a custom axis. {@code null} means the grant is
     * unrestricted for this resource and action - the row-level equivalent of {@code ALL}.
     */
    @Column(name = "dimension", length = 64)
    private String dimension;

    /** The value the dimension is restricted to. Required whenever {@link #dimension} is set. */
    @Column(name = "dimension_value", length = 255)
    private String dimensionValue;

    /**
     * When the grant stops applying. Time-boxing is the difference between a coverage arrangement and a
     * permanent privilege nobody remembers granting; the query filters on it rather than relying on
     * anything to come back and clean up.
     */
    @Column(name = "expires_at")
    private Instant expiresAt;

    public boolean isUnrestricted() {
        return dimension == null || dimension.isBlank();
    }
}
