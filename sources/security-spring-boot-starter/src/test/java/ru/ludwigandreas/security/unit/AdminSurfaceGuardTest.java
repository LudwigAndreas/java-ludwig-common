package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminAccessEvaluator;
import ru.ludwigandreas.security.admin.AdminAuthenticated;
import ru.ludwigandreas.security.admin.AdminGrants;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.security.admin.AdminReasonRequiredException;
import ru.ludwigandreas.security.admin.AdminSurfaceGuard;
import ru.ludwigandreas.security.admin.TenantScope;
import ru.ludwigandreas.security.principal.Credential;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.security.web.PatCredentialNotPermittedException;

class AdminSurfaceGuardTest {

    private final List<Object> deniedObjects = new ArrayList<>();
    private final AdminOperationCatalog catalog =
            new AdminOperationCatalog(List.of(AdminOperationCatalogTest.area("outbox")));
    private final AdminGrants grants = new AdminGrants(Map.of(
            "outbox.read", List.of("NOTIFICATION_ADMIN", "NOTIFICATION_SUPPORT"),
            "outbox.operate", List.of("NOTIFICATION_ADMIN"),
            "outbox.privileged", List.of("NOTIFICATION_ADMIN")), catalog);
    private final AuthorizationEventPublisher publisher = new AuthorizationEventPublisher() {
        @Override
        public <T> void publishAuthorizationEvent(Supplier<Authentication> authentication, T object,
                                                  AuthorizationDecision decision) {
            if (!decision.isGranted()) {
                deniedObjects.add(object);
            }
        }
    };

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clear() {
        // The dispatcher always calls afterCompletion once preHandle returned true; tests that stop at preHandle
        // stand in for it here, or the bound invocation leaks into the next test on this thread.
        guard(false).afterCompletion(request(), response, null, null);
        SecurityContextHolder.clearContext();
    }

    /** Stands in for a module's administrative controller. */
    static class Fixture {

        @AdminAccess("outbox.read")
        public void read() {
        }

        @AdminAccess("outbox.operate")
        public void operate() {
        }

        @AdminAccess("outbox.privileged")
        public void discard() {
        }

        @AdminAuthenticated
        public void discover() {
        }

        public void forgotten() {
        }
    }

    private static HandlerMethod handler(String name) throws NoSuchMethodException {
        Method method = Fixture.class.getMethod(name);
        return new HandlerMethod(new Fixture(), method);
    }

    private static LudwigPrincipal principal(PrincipalType type, String tenant, String... roles) {
        return LudwigPrincipal.builder().subject("alice").type(type).tenantId(tenant).roles(List.of(roles)).build();
    }

    private static void authenticate(LudwigPrincipal principal, Credential credential) {
        SecurityContextHolder.getContext().setAuthentication(new LudwigAuthentication(principal, credential));
    }

    private AdminSurfaceGuard guard(boolean allowPats) {
        return new AdminSurfaceGuard(catalog, new AdminAccessEvaluator(grants, publisher), allowPats);
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("POST", "/admin/v1/outbox/messages/1/retry");
    }

    @Test
    @DisplayName("no authentication, or an anonymous one, is a 401 before anything else")
    void refusesTheUnauthenticated() {
        assertThatThrownBy(() -> guard(false).preHandle(request(), response, handler("read")))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);

        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("anon", null));
        assertThatThrownBy(() -> guard(false).preHandle(request(), response, handler("read")))
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    @DisplayName("a partner is refused whatever roles it holds")
    void refusesPartners() {
        authenticate(principal(PrincipalType.PARTNER, null, "NOTIFICATION_ADMIN"), Credential.DIRECT);

        assertThatThrownBy(() -> guard(false).preHandle(request(), response, handler("read")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a PAT-credentialed caller is refused with the credential-not-permitted type unless allowed")
    void refusesPatsByDefault() throws Exception {
        authenticate(principal(PrincipalType.USER, null, "NOTIFICATION_ADMIN"), Credential.personalAccessToken("p1"));

        assertThatThrownBy(() -> guard(false).preHandle(request(), response, handler("read")))
                .isInstanceOfSatisfying(PatCredentialNotPermittedException.class, e -> assertThat(
                        e.toDefinition().code()).isEqualTo("ludwig.pat.credential-not-permitted"));
        assertThat(guard(true).preHandle(request(), response, handler("read"))).isTrue();
    }

    @Test
    @DisplayName("an ungranted operation is a 403 and is published as a denial carrying the handler")
    void refusesUngrantedOperations() throws Exception {
        authenticate(principal(PrincipalType.USER, null, "NOTIFICATION_SUPPORT"), Credential.DIRECT);
        HandlerMethod operate = handler("operate");

        assertThat(guard(false).preHandle(request(), response, handler("read"))).isTrue();
        assertThatThrownBy(() -> guard(false).preHandle(request(), response, operate))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(deniedObjects).containsExactly(operate);
    }

    @Test
    @DisplayName("only resolved roles count: a role in a permission or an attribute grants nothing")
    void onlyResolvedRolesCount() {
        LudwigPrincipal claimed = LudwigPrincipal.builder().subject("alice").type(PrincipalType.USER)
                .permission("ROLE_NOTIFICATION_ADMIN").attribute("roles", "NOTIFICATION_ADMIN").build();
        authenticate(claimed, Credential.DIRECT);

        assertThatThrownBy(() -> guard(false).preHandle(request(), response, handler("read")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a privileged call without a reason is a 400, but only once the grant is established")
    void privilegedRequiresAReasonAfterTheGrant() throws Exception {
        authenticate(principal(PrincipalType.USER, null, "NOTIFICATION_SUPPORT"), Credential.DIRECT);
        assertThatThrownBy(() -> guard(false).preHandle(request(), response, handler("discard")))
                .isInstanceOf(AccessDeniedException.class);

        authenticate(principal(PrincipalType.USER, null, "NOTIFICATION_ADMIN"), Credential.DIRECT);
        assertThatThrownBy(() -> guard(false).preHandle(request(), response, handler("discard")))
                .isInstanceOf(AdminReasonRequiredException.class);

        MockHttpServletRequest blank = request();
        blank.addHeader(AdminInvocation.REASON_HEADER, "   ");
        assertThatThrownBy(() -> guard(false).preHandle(blank, response, handler("discard")))
                .isInstanceOf(AdminReasonRequiredException.class);

        MockHttpServletRequest tooLong = request();
        tooLong.addHeader(AdminInvocation.REASON_HEADER, "x".repeat(AdminInvocation.MAX_REASON_LENGTH + 1));
        assertThatThrownBy(() -> guard(false).preHandle(tooLong, response, handler("discard")))
                .isInstanceOf(AdminReasonRequiredException.class);
    }

    @Test
    @DisplayName("an authorized call binds the invocation with its trimmed reason, and completion unbinds it")
    void bindsTheInvocation() throws Exception {
        authenticate(principal(PrincipalType.USER, "acme", "NOTIFICATION_ADMIN"), Credential.DIRECT);
        MockHttpServletRequest withReason = request();
        withReason.addHeader(AdminInvocation.REASON_HEADER, "  INC-4411 ");
        AdminSurfaceGuard guard = guard(false);

        assertThat(guard.preHandle(withReason, response, handler("discard"))).isTrue();
        AdminInvocation invocation = AdminInvocation.require();
        assertThat(invocation.operation().id()).isEqualTo("outbox.privileged");
        assertThat(invocation.reason()).isEqualTo("INC-4411");
        assertThat(invocation.tenantScope()).isEqualTo(TenantScope.confinedTo("acme"));

        guard.afterCompletion(withReason, response, handler("discard"), null);
        assertThat(AdminInvocation.current()).isEmpty();
    }

    @Test
    @DisplayName("an operate call needs no reason")
    void operateNeedsNoReason() throws Exception {
        authenticate(principal(PrincipalType.USER, null, "NOTIFICATION_ADMIN"), Credential.DIRECT);
        AdminSurfaceGuard guard = guard(false);

        assertThat(guard.preHandle(request(), response, handler("operate"))).isTrue();
        assertThat(AdminInvocation.require().statedReason()).isEmpty();
        guard.afterCompletion(request(), response, handler("operate"), null);
    }

    @Test
    @DisplayName("a discovery handler admits any authenticated caller; a handler with neither annotation is refused")
    void markerAndFailClosed() throws Exception {
        authenticate(principal(PrincipalType.USER, null), Credential.DIRECT);

        assertThat(guard(false).preHandle(request(), response, handler("discover"))).isTrue();
        assertThat(AdminInvocation.current()).isEmpty();
        assertThatThrownBy(() -> guard(false).preHandle(request(), response, handler("forgotten")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a service principal is not tenant-confined; a user without a tenant is confined to none")
    void tenantScope() throws Exception {
        authenticate(principal(PrincipalType.SERVICE, null, "NOTIFICATION_ADMIN"), Credential.DIRECT);
        AdminSurfaceGuard guard = guard(false);
        guard.preHandle(request(), response, handler("read"));
        assertThat(AdminInvocation.require().tenantScope().permits("globex")).isTrue();
        guard.afterCompletion(request(), response, null, null);

        authenticate(principal(PrincipalType.USER, null, "NOTIFICATION_ADMIN"), Credential.DIRECT);
        guard.preHandle(request(), response, handler("read"));
        TenantScope scope = AdminInvocation.require().tenantScope();
        assertThat(scope.permits("globex")).isFalse();
        assertThat(scope.permits(null)).isTrue();
        guard.afterCompletion(request(), response, null, null);
    }
}
