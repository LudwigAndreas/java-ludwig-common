package ru.ludwigandreas.identity.admin.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.cache.api.LudwigCache;
import ru.ludwigandreas.identity.admin.service.IdentityAdminViews.EffectiveAuthorities;
import ru.ludwigandreas.identity.admin.service.IdentityAdminViews.GrantRequest;
import ru.ludwigandreas.identity.admin.service.IdentityAdminViews.GrantView;
import ru.ludwigandreas.identity.admin.service.IdentityAdminViews.PartnerRequest;
import ru.ludwigandreas.identity.admin.service.IdentityAdminViews.PartnerView;
import ru.ludwigandreas.identity.entity.PartnerStatus;
import ru.ludwigandreas.identity.entity.SecurityGrantEntity;
import ru.ludwigandreas.identity.entity.SecurityPartnerEntity;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.repository.IdentityAdminQueries;
import ru.ludwigandreas.identity.repository.SecurityGrantRepository;
import ru.ludwigandreas.identity.repository.SecurityPartnerRepository;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminOutcome;
import ru.ludwigandreas.security.admin.TenantScope;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityResolver;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.data.DataScopeRegistry;
import ru.ludwigandreas.security.data.ScopeDimension;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * The {@code identity} area's operations: the administrator-owned tables - the partner registry and data-scope
 * grants - and what a subject is entitled to.
 *
 * <p>Every change is recorded through {@link AdminAudit} in the transaction that makes it, which closes this module's
 * documented gap: the rows carry {@code created_by}/{@code updated_by}, which say who changed them last, and the
 * trail now says who changed what, when and why. A partner change evicts that partner's cached authorities after
 * commit, as the Kafka projection does for a user; grants are read per request, so a grant change takes effect at
 * once.
 *
 * <p>A person is confined to subjects of their own tenant, judged by the tenant the projection records for the
 * subject; the partner registry carries no tenant and is not confined.
 */
public class IdentityAdminService {

    private static final String PARTNER = "partner";
    private static final String GRANT = "data-scope-grant";
    private static final String SUBJECT = "subject";

    private final SecurityPartnerRepository partners;
    private final SecurityGrantRepository grants;
    private final SecurityUserRepository users;
    private final AuthorityResolver resolver;
    private final LudwigCache<PrincipalRef, Authorities> authorityCache;
    private final DataScopeRegistry scopes;
    private final AdminAudit audit;
    private final Clock clock;

    /**
     * Creates the service.
     *
     * @param tables         the identity projection's tables
     * @param resolver       the authority resolver, uncached
     * @param authorityCache the authority cache, evicted after a partner change
     * @param scopes         the data-scope mappings a grant's dimension is checked against, or null
     * @param audit          the administrative audit
     * @param clock          what "in force" is judged against
     */
    public IdentityAdminService(Tables tables, AuthorityResolver resolver,
                                LudwigCache<PrincipalRef, Authorities> authorityCache, DataScopeRegistry scopes,
                                AdminAudit audit, Clock clock) {
        this.partners = tables.partners();
        this.grants = tables.grants();
        this.users = tables.users();
        this.resolver = resolver;
        this.authorityCache = authorityCache;
        this.scopes = scopes;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * The identity projection's repositories.
     *
     * @param partners the partner registry
     * @param grants   the data-scope grants
     * @param users    the projected users
     */
    public record Tables(SecurityPartnerRepository partners, SecurityGrantRepository grants,
                         SecurityUserRepository users) {
    }

    /**
     * Every partner, ordered by code.
     *
     * @return the partners
     */
    @Transactional(readOnly = true)
    public List<PartnerView> partners() {
        return partners.findAll(Sort.by("code")).stream().map(PartnerView::of).toList();
    }

    /**
     * One partner.
     *
     * @param code its code
     * @return the partner
     */
    @Transactional(readOnly = true)
    public PartnerView partner(String code) {
        return PartnerView.of(requirePartner(code));
    }

    /**
     * Onboards a partner.
     *
     * @param request the partner; its code is required
     * @return the partner as stored
     */
    @Transactional
    public PartnerView onboard(PartnerRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw IdentityAdminException.partnerCodeRequired();
        }
        if (partners.lookupByCode(request.code()).isPresent()) {
            throw IdentityAdminException.partnerExists(request.code());
        }
        SecurityPartnerEntity partner = new SecurityPartnerEntity();
        partner.setCode(request.code());
        apply(partner, request);
        partner.setStatus(PartnerStatus.ACTIVE);
        SecurityPartnerEntity saved = partners.save(partner);
        audit.record(PARTNER, saved.getCode(), AdminOutcome.SUCCEEDED, Map.of("change", "onboarded"));
        return PartnerView.of(saved);
    }

    /**
     * Replaces a partner's name, certificate identifiers and roles.
     *
     * @param code            its code
     * @param request         the new values
     * @param expectedVersion the version the caller read, from {@code If-Match}
     * @return the partner as stored
     */
    @Transactional
    public PartnerView replace(String code, PartnerRequest request, long expectedVersion) {
        SecurityPartnerEntity partner = requirePartner(code);
        if (partner.getVersion() != expectedVersion) {
            throw IdentityAdminException.stalePartner(code, expectedVersion);
        }
        apply(partner, request);
        SecurityPartnerEntity saved = partners.saveAndFlush(partner);
        audit.record(PARTNER, code, AdminOutcome.SUCCEEDED, Map.of("change", "replaced"));
        authorityCache.evictAfterCommit(PrincipalRef.partner(code));
        return PartnerView.of(saved);
    }

    /**
     * Suspends or reactivates a partner. A suspended partner is refused authentication no later than the authority
     * cache's TTL after commit; this replica evicts at once.
     *
     * @param code   its code
     * @param status the status to set
     * @return the partner as stored
     */
    @Transactional
    public PartnerView setStatus(String code, PartnerStatus status) {
        SecurityPartnerEntity partner = requirePartner(code);
        partner.setStatus(status);
        SecurityPartnerEntity saved = partners.saveAndFlush(partner);
        audit.record(PARTNER, code, AdminOutcome.SUCCEEDED, Map.of("change", status.name().toLowerCase(Locale.ROOT)));
        authorityCache.evictAfterCommit(PrincipalRef.partner(code));
        return PartnerView.of(saved);
    }

    /**
     * Grants matching the filter, confined to the caller's tenant's subjects.
     *
     * @param subject  the grantee, or null
     * @param resource the resource type, or null
     * @param active   true for in force, false for lapsed, null for both
     * @return the grants
     */
    @Transactional(readOnly = true)
    public List<GrantView> grants(String subject, String resource, Boolean active) {
        List<SecurityGrantEntity> found = StreamSupport.stream(grants.findAll(
                IdentityAdminQueries.grants(subject, resource, active, clock.instant())).spliterator(), false)
                .toList();
        TenantScope scope = AdminInvocation.require().tenantScope();
        if (!scope.confined()) {
            return found.stream().map(GrantView::of).toList();
        }
        Set<String> subjects = found.stream().map(SecurityGrantEntity::getSubject).collect(Collectors.toSet());
        Map<String, SecurityUserEntity> byId = users.findAllById(subjects).stream()
                .collect(Collectors.toMap(SecurityUserEntity::getId, Function.identity()));
        return found.stream()
                .filter(grant -> grant.getPrincipalType() == PrincipalType.USER)
                .filter(grant -> Optional.ofNullable(byId.get(grant.getSubject()))
                        .map(user -> scope.permits(user.getTenantId())).orElse(false))
                .map(GrantView::of)
                .toList();
    }

    /**
     * Creates a data-scope grant.
     *
     * @param request the grant
     * @return the grant as stored
     */
    @Transactional
    public GrantView grant(GrantRequest request) {
        if (blank(request.subject()) || request.principalType() == null || blank(request.resourceType())
                || blank(request.action()) || !blank(request.dimension()) && blank(request.dimensionValue())) {
            throw IdentityAdminException.invalidGrant();
        }
        if (request.principalType() == PrincipalType.USER) {
            requireReachableUser(request.subject());
        }
        if (!blank(request.dimension()) && !accepts(request.resourceType(), request.dimension())) {
            throw IdentityAdminException.dimensionNotAccepted(request.resourceType(), request.dimension());
        }
        SecurityGrantEntity grant = new SecurityGrantEntity();
        grant.setSubject(request.subject());
        grant.setPrincipalType(request.principalType());
        grant.setResourceType(request.resourceType());
        grant.setAction(request.action());
        grant.setDimension(blank(request.dimension()) ? null : request.dimension());
        grant.setDimensionValue(blank(request.dimension()) ? null : request.dimensionValue());
        grant.setExpiresAt(request.expiresAt());
        SecurityGrantEntity saved = grants.save(grant);
        audit.record(GRANT, saved.getId().toString(), AdminOutcome.SUCCEEDED, Map.of("change", "granted",
                "subject", saved.getSubject(), "resourceType", saved.getResourceType(), "action", saved.getAction()));
        return GrantView.of(saved);
    }

    /**
     * Revokes a grant by lapsing it now; the row is kept, so the listing and the trail still show it.
     *
     * @param id the grant
     * @return the grant as stored
     */
    @Transactional
    public GrantView revoke(UUID id) {
        SecurityGrantEntity grant = grants.findById(id).orElseThrow(() -> IdentityAdminException.grantNotFound(id));
        if (grant.getPrincipalType() == PrincipalType.USER) {
            requireReachableUser(grant.getSubject());
        }
        Instant now = clock.instant();
        if (grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(now)) {
            grant.setExpiresAt(now);
        }
        SecurityGrantEntity saved = grants.save(grant);
        audit.record(GRANT, id.toString(), AdminOutcome.SUCCEEDED, Map.of("change", "revoked",
                "subject", saved.getSubject()));
        return GrantView.of(saved);
    }

    /**
     * What the authority resolver answers for a user now, bypassing the cache, with the grants in force. Recorded,
     * because it discloses one person's entitlements.
     *
     * @param subject the user
     * @return the effective authorities
     */
    @Transactional(readOnly = true)
    public EffectiveAuthorities authorities(String subject) {
        SecurityUserEntity user = requireReachableUser(subject);
        Authorities resolved = resolver.resolve(PrincipalRef.user(subject));
        List<GrantView> inForce = StreamSupport.stream(grants.findAll(
                IdentityAdminQueries.grants(subject, null, true, clock.instant())).spliterator(), false)
                .filter(grant -> grant.getPrincipalType() == PrincipalType.USER)
                .map(GrantView::of)
                .toList();
        audit.record(SUBJECT, subject, AdminOutcome.SUCCEEDED, Map.of("inspected", "authorities"));
        return new EffectiveAuthorities(subject, user.getTenantId(), new LinkedHashSet<>(resolved.roles()),
                resolved.attributes(), inForce);
    }

    private boolean accepts(String resource, String dimension) {
        return scopes != null && scopes.find(resource)
                .map(mapping -> mapping.supports(ScopeDimension.of(dimension))).orElse(false);
    }

    /** A person may reach only subjects of their own tenant, and is told "not found" for any other. */
    private SecurityUserEntity requireReachableUser(String subject) {
        TenantScope scope = AdminInvocation.require().tenantScope();
        return users.findById(subject).filter(user -> scope.permits(user.getTenantId()))
                .orElseThrow(() -> IdentityAdminException.subjectNotFound(subject));
    }

    private SecurityPartnerEntity requirePartner(String code) {
        return partners.lookupByCode(code).orElseThrow(() -> IdentityAdminException.partnerNotFound(code));
    }

    private static void apply(SecurityPartnerEntity partner, PartnerRequest request) {
        partner.setDisplayName(request.displayName());
        partner.setSpiffeId(request.spiffeId());
        partner.setDnsSan(request.dnsSan());
        partner.setSubjectDn(request.subjectDn());
        partner.setCertificateHash(request.certificateHash());
        partner.setRoles(request.roles() == null ? new LinkedHashSet<>() : new LinkedHashSet<>(request.roles()));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
