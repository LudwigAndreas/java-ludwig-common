package ru.ludwigandreas.export.security;

import java.util.LinkedHashSet;
import java.util.Set;
import ru.ludwigandreas.security.authz.Authorities;
import ru.ludwigandreas.security.authz.AuthorityResolver;
import ru.ludwigandreas.security.authz.PrincipalRef;

/**
 * Resolves a requester's authorities through {@code security-spring-boot-starter}.
 *
 * <p>Roles and permissions are folded into one set because that is what a report definition's
 * {@code visibleFor} and {@code requiredAuthorities} are matched against, and keeping them apart
 * here would push the distinction into every definition for no benefit: a column visible to
 * {@code ROLE_FINANCE} and a column visible to {@code report:margin:read} are the same kind of
 * statement about entitlement.
 *
 * <p>Always resolved as a <em>user</em> reference. A report is requested by a person; a service
 * account producing one on somebody's behalf is the subject of that person's grant, not its own, and
 * resolving it as a workload would give it whatever the workload may see rather than whatever the
 * requester may.
 */
public class SecurityReportAuthorities implements ReportAuthorities {

    private final AuthorityResolver resolver;

    public SecurityReportAuthorities(AuthorityResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Set<String> forPrincipal(String principalId) {
        if (principalId == null || principalId.isBlank()) {
            return Set.of();
        }
        Authorities authorities = resolver.resolve(PrincipalRef.user(principalId));
        Set<String> combined = new LinkedHashSet<>(authorities.roles());
        combined.addAll(authorities.permissions());
        return Set.copyOf(combined);
    }
}
