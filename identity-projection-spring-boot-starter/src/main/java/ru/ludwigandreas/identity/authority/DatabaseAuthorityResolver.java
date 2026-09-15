package ru.ludwigandreas.identity.authority;

import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.identity.config.IdentityProjectionProperties;
import ru.ludwigandreas.identity.entity.SecurityPartnerEntity;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.repository.SecurityPartnerRepository;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityResolver;
import ru.ludwigandreas.security.authz.PrincipalRef;

/**
 * Resolves roles from the local projection: users from the OIDC stream, partners from the partner
 * registry, peer services from configuration.
 *
 * <p>Peer services come from configuration rather than a table because their grants are part of the
 * deployment topology - which service may call which - and that belongs in the same review and rollout as
 * the code that makes the call. Nobody grants a service access at runtime.
 *
 * <p>A subject that is not in the projection, or is disabled, resolves to {@link Authorities#none()}
 * rather than an exception. That is the correct answer and not an error condition: a valid token from an
 * employee who was never onboarded to this service is exactly the case, and it must end in a clean 403
 * rather than a 500.
 */
@Slf4j
@RequiredArgsConstructor
public class DatabaseAuthorityResolver implements AuthorityResolver {

    private final SecurityUserRepository users;
    private final SecurityPartnerRepository partners;
    private final IdentityProjectionProperties properties;

    @Override
    @Transactional(readOnly = true)
    public Authorities resolve(PrincipalRef ref) {
        return switch (ref.type()) {
            case USER -> forUser(ref.subject());
            case PARTNER -> forPartner(ref.subject());
            case SERVICE -> forService(ref.subject());
        };
    }

    private Authorities forUser(String subject) {
        return users.findById(subject)
                .filter(SecurityUserEntity::isActive)
                .map(user -> {
                    Authorities.AuthoritiesBuilder authorities = Authorities.builder().roles(user.getRoles());
                    if (user.getTenantId() != null && !user.getTenantId().isBlank()) {
                        authorities.attribute(Authorities.TENANT_ATTRIBUTE, user.getTenantId());
                    }
                    return authorities.build();
                })
                .orElseGet(() -> {
                    log.debug("No active projected user for subject {}; resolving to no authorities", subject);
                    return Authorities.none();
                });
    }

    private Authorities forPartner(String code) {
        return partners.lookupByCode(code)
                .map(SecurityPartnerEntity::getRoles)
                .map(roles -> Authorities.builder()
                        .roles(roles)
                        // A partner is its own partner scope value; recording it as an attribute too means
                        // a PARTNER-dimension policy resolves the same way for a partner certificate and
                        // for a user acting on that partner's behalf.
                        .attribute(Authorities.PARTNER_ATTRIBUTE, code)
                        .build())
                .orElse(Authorities.none());
    }

    private Authorities forService(String workloadId) {
        Map<String, Set<String>> serviceRoles = properties.getServiceRoles();
        Set<String> roles = serviceRoles.get(workloadId);
        if (roles == null || roles.isEmpty()) {
            log.debug("No configured grant for workload {}; resolving to no authorities", workloadId);
            return Authorities.none();
        }
        return Authorities.builder().roles(roles).build();
    }
}
