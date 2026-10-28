package ru.ludwigandreas.usersettings.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.authz.PrincipalRef;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.exception.SettingsAccessDeniedException;
import ru.ludwigandreas.usersettings.exception.SettingsSubjectNotFoundException;
import ru.ludwigandreas.usersettings.resolve.SettingsAccess;
import ru.ludwigandreas.usersettings.resolve.SettingsAccessPolicy;

/**
 * "Your own settings, or a {@code settings} administrative operation - and never across a tenant boundary."
 *
 * <p>The cross-tenant case is the one worth having a test for: being inside an administrative operation is not
 * enough, and a policy that checked only that would let an administrator of one organization read another
 * organization's users by knowing a subject id.
 */
class SettingsAccessPolicyTest {

    /** The administrative operation the current request performs, as the surface guard would have bound it. */
    private Optional<AdminInvocation> invocation = Optional.empty();

    private final SettingsAccessPolicy policy = new SettingsAccessPolicy(true, true, () -> invocation);

    private void inOperation(String operationId) {
        LudwigPrincipal principal = ru.ludwigandreas.security.principal.SecurityPrincipals.require();
        invocation = Optional.of(new AdminInvocation(AdminOperation.of(operationId, "key"), principal, null));
    }

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
    @DisplayName("an administrator in the same tenant, inside a settings operation, is ADMIN")
    void administrator_in_the_same_tenant_is_allowed() {
        authenticate("admin-1", "acme");
        inOperation("settings.read");

        assertThat(policy.check(subject("user-1", "acme"))).isEqualTo(SettingsAccess.ADMIN);
    }

    @Test
    @DisplayName("an administrator of another tenant is answered 404, not 403")
    void administrator_of_another_tenant_is_refused() {
        authenticate("admin-1", "globex");
        inOperation("settings.read");

        assertThatThrownBy(() -> policy.check(subject("user-1", "acme")))
                .isInstanceOf(SettingsSubjectNotFoundException.class);
    }

    @Test
    @DisplayName("outside an administrative operation nobody reads somebody else's settings, whatever their roles")
    void other_subject_outside_an_operation_is_refused() {
        // The role a library used to check is now the service's grant, checked by the surface guard.
        authenticate("user-2", "acme", "ROLE_SETTINGS_ADMIN");

        assertThatThrownBy(() -> policy.check(subject("user-1", "acme")))
                .isInstanceOf(SettingsAccessDeniedException.class);
    }

    @Test
    @DisplayName("another area's administrative operation does not reach settings")
    void another_areas_operation_is_refused() {
        authenticate("admin-1", "acme");
        inOperation("jobs.read");

        assertThatThrownBy(() -> policy.check(subject("user-1", "acme")))
                .isInstanceOf(SettingsAccessDeniedException.class);
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
        SettingsAccessPolicy strict = new SettingsAccessPolicy(false, true, () -> invocation);

        assertThatThrownBy(() -> strict.check(subject("user-1", "acme")))
                .isInstanceOf(SettingsAccessDeniedException.class);
    }

    @Test
    @DisplayName("a peer service resolves any subject's settings without an administrative operation")
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
    @DisplayName("a peer service is refused when the deployment holds peers to the administrative surface")
    void service_principals_can_be_refused() {
        SettingsAccessPolicy strict = new SettingsAccessPolicy(true, false, () -> invocation);
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

        authenticate("admin-1", "acme");
        inOperation("settings.operate");
        assertThatCode(() -> policy.requireAdmin(subject("admin-1", "acme"))).doesNotThrowAnyException();
    }
}
