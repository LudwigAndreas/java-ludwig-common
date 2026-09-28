package ru.ludwigandreas.odatafilter.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Default {@link FilterPrincipalResolver} used when Spring Security is on the classpath: reports
 * the authenticated principal's granted authorities (e.g. {@code ROLE_ADMIN}) verbatim, so
 * {@code @Filterable(roles = "ROLE_ADMIN")} lines up with however the application already names
 * its authorities.
 */
public class SpringSecurityFilterPrincipalResolver implements FilterPrincipalResolver {

    @Override
    public Set<String> resolveRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
    }
}
