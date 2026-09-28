package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.exception.SettingsAccessDeniedException;
import ru.ludwigandreas.usersettings.resolve.SettingsAccess;
import ru.ludwigandreas.usersettings.resolve.SettingsAccessPolicy;

/**
 * "Your own settings, or the administrative authority - and never across a tenant boundary."
 *
 * <p>The cross-tenant case is the one worth having a test for: holding the authority is not enough,
 * and a policy that checked only the authority would let an administrator of one organization read
 * another organization's users by knowing a subject id.
 */
class SettingsAccessPolicyTest {

    private static final String ADMIN_AUTHORITY = "ROLE_SETTINGS_ADMIN";

    private final SettingsAccessPolicy policy = new SettingsAccessPolicy(ADMIN_AUTHORITY, true, true);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String subject, String tenant, String... roles) {
        LudwigPrincipal principal = LudwigPrincipal.builder()
                .subject(subject)
                .type(PrincipalType.USER)
                .tenantId(tenant)
                .roles(List.of(roles))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
    }

    private static SettingsSubject subject(String id, String tenant) {
        return new SettingsSubject(PrincipalRef.user(id), tenant);
    }

    @Test
    @DisplayName("a caller reading their own settings is SELF")
    void own_settings_are_self_access() {
        authenticate("user-1", "acme");

        assertThat(policy.check(subject("user-1", "acme"))).isEqualTo(SettingsAccess.SELF);
    }

    @Test
    @DisplayName("an administrator in the same tenant is ADMIN")
    void administrator_in_the_same_tenant_is_allowed() {
        authenticate("admin-1", "acme", ADMIN_AUTHORITY);

        assertThat(policy.check(subject("user-1", "acme"))).isEqualTo(SettingsAccess.ADMIN);
    }

    @Test
    @DisplayName("an administrator of another tenant is refused")
    void administrator_of_another_tenant_is_refused() {
        authenticate("admin-1", "globex", ADMIN_AUTHORITY);

        assertThatThrownBy(() -> policy.check(subject("user-1", "acme")))
                .isInstanceOf(SettingsAccessDeniedException.class);
    }

    @Test
    @DisplayName("a caller without the authority cannot read somebody else's settings")
    void other_subject_without_authority_is_refused() {
        authenticate("user-2", "acme");

        assertThatThrownBy(() -> policy.check(subject("user-1", "acme")))
                .isInstanceOf(SettingsAccessDeniedException.class);
    }

    @Test
    @DisplayName("the authority is accepted whether or not the property carries the ROLE_ prefix")
    void admin_authority_is_matched_with_or_without_the_role_prefix() {
        // A property written as SETTINGS_ADMIN would otherwise match nothing, every administrative
        // access would be refused, and it would read as a permissions bug.
        SettingsAccessPolicy unprefixed = new SettingsAccessPolicy("SETTINGS_ADMIN", true, true);
        authenticate("admin-1", "acme", "SETTINGS_ADMIN");

        assertThat(unprefixed.check(subject("user-1", "acme"))).isEqualTo(SettingsAccess.ADMIN);
    }

    @Test
    @DisplayName("a subject id that matches across principal types is not the same principal")
    void subject_and_type_both_have_to_match() {
        // A partner id that happens to equal a user's sub must not hand one the other's settings -
        // the same reason PrincipalRef carries a type at all.
        authenticate("shared-id", "acme");

        assertThatThrownBy(() -> policy.check(
                new SettingsSubject(PrincipalRef.partner("shared-id"), "acme")))
                .isInstanceOf(SettingsAccessDeniedException.class);
    }

    @Test
    @DisplayName("in-process code with no principal is SYSTEM when that is allowed")
    void unauthenticated_is_system_access_by_default() {
        assertThat(policy.check(subject("user-1", "acme"))).isEqualTo(SettingsAccess.SYSTEM);
    }

    @Test
    @DisplayName("in-process code is refused when unauthenticated access is switched off")
    void unauthenticated_is_refused_when_disallowed() {
        SettingsAccessPolicy strict = new SettingsAccessPolicy(ADMIN_AUTHORITY, false, true);

        assertThatThrownBy(() -> strict.check(subject("user-1", "acme")))
                .isInstanceOf(SettingsAccessDeniedException.class);
    }

    @Test
    @DisplayName("a peer service resolves any subject's settings without an administrative authority")
    void service_principals_are_system_callers() {
        // The self-or-admin rule is about people. A notification service resolving a recipient's
        // locale while handling another service's request is not a user reading somebody else's
        // preferences, and holding it to "is this your own subject id" asks a question with no
        // meaningful answer - which is the failure this case exists to pin down.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(LudwigPrincipal.builder()
                        .subject("spiffe://mesh/ns/orders/sa/orders")
                        .type(PrincipalType.SERVICE)
                        .build(), "n/a", List.of()));

        assertThat(policy.check(subject("user-1", "acme"))).isEqualTo(SettingsAccess.SYSTEM);
    }

    @Test
    @DisplayName("a peer service is refused when the deployment holds peers to the admin authority")
    void service_principals_can_be_refused() {
        SettingsAccessPolicy strict = new SettingsAccessPolicy(ADMIN_AUTHORITY, true, false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(LudwigPrincipal.builder()
                        .subject("spiffe://mesh/ns/orders/sa/orders")
                        .type(PrincipalType.SERVICE)
                        .build(), "n/a", List.of()));

        assertThatThrownBy(() -> strict.check(subject("user-1", "acme")))
                .isInstanceOf(SettingsAccessDeniedException.class);
    }

    @Test
    @DisplayName("requireAdmin refuses a caller who merely owns the subject")
    void require_admin_is_not_satisfied_by_being_the_subject() {
        // Writes at a role or tenant scope are never self-service, however the subject is named.
        authenticate("user-1", "acme");

        assertThatThrownBy(() -> policy.requireAdmin(subject("user-1", "acme")))
                .isInstanceOf(SettingsAccessDeniedException.class);

        authenticate("admin-1", "acme", ADMIN_AUTHORITY);
        assertThatCode(() -> policy.requireAdmin(subject("admin-1", "acme"))).doesNotThrowAnyException();
    }
}
