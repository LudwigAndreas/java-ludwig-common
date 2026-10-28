package ru.ludwigandreas.identity.admin.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import ru.ludwigandreas.identity.entity.PartnerStatus;
import ru.ludwigandreas.identity.entity.SecurityGrantEntity;
import ru.ludwigandreas.identity.entity.SecurityPartnerEntity;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * What the {@code identity} area answers with and accepts.
 */
public final class IdentityAdminViews {

    private IdentityAdminViews() {
    }

    /**
     * One partner.
     *
     * @param code            its stable code
     * @param displayName     its name
     * @param status          {@code ACTIVE} or {@code SUSPENDED}
     * @param spiffeId        certificate identifier
     * @param dnsSan          certificate identifier
     * @param subjectDn       certificate identifier
     * @param certificateHash certificate identifier
     * @param roles           the roles it holds
     * @param version         the version an {@code If-Match} names to change it
     */
    public record PartnerView(String code, String displayName, PartnerStatus status, String spiffeId, String dnsSan,
                              String subjectDn, String certificateHash, Set<String> roles, long version) {

        static PartnerView of(SecurityPartnerEntity partner) {
            return new PartnerView(partner.getCode(), partner.getDisplayName(), partner.getStatus(),
                    partner.getSpiffeId(), partner.getDnsSan(), partner.getSubjectDn(), partner.getCertificateHash(),
                    Set.copyOf(partner.getRoles()), partner.getVersion());
        }
    }

    /**
     * A partner as an administrator writes it.
     *
     * @param code            its stable code; required to onboard, ignored on a replace
     * @param displayName     its name
     * @param spiffeId        certificate identifier
     * @param dnsSan          certificate identifier
     * @param subjectDn       certificate identifier
     * @param certificateHash certificate identifier
     * @param roles           the roles it holds
     */
    public record PartnerRequest(String code, String displayName, String spiffeId, String dnsSan, String subjectDn,
                                 String certificateHash, Set<String> roles) {
    }

    /**
     * One data-scope grant.
     *
     * @param id             its id
     * @param subject        the grantee
     * @param principalType  the grantee's kind
     * @param resourceType   the resource it applies to
     * @param action         the action it permits
     * @param dimension      the dimension it scopes, or null for an unscoped grant
     * @param dimensionValue the value of that dimension
     * @param expiresAt      when it lapses, or null for never; a revoked grant has lapsed
     */
    public record GrantView(UUID id, String subject, PrincipalType principalType, String resourceType, String action,
                            String dimension, String dimensionValue, Instant expiresAt) {

        static GrantView of(SecurityGrantEntity grant) {
            return new GrantView(grant.getId(), grant.getSubject(), grant.getPrincipalType(),
                    grant.getResourceType(), grant.getAction(), grant.getDimension(), grant.getDimensionValue(),
                    grant.getExpiresAt());
        }
    }

    /**
     * A grant as an administrator writes it.
     *
     * @param subject        the grantee
     * @param principalType  the grantee's kind
     * @param resourceType   the resource
     * @param action         the action
     * @param dimension      the dimension, or null
     * @param dimensionValue the dimension's value
     * @param expiresAt      when it lapses, or null for never
     */
    public record GrantRequest(String subject, PrincipalType principalType, String resourceType, String action,
                               String dimension, String dimensionValue, Instant expiresAt) {
    }

    /**
     * What the authority resolver answers for a subject now, with the grants in force.
     *
     * @param subject    the subject
     * @param tenant     its tenant, when it has one
     * @param roles      its resolved roles
     * @param attributes its resolved attributes
     * @param grants     its data-scope grants in force
     */
    public record EffectiveAuthorities(String subject, String tenant, Set<String> roles,
                                       Map<String, String> attributes, List<GrantView> grants) {
    }
}
