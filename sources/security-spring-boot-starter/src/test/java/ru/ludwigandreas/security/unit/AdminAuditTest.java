package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.security.admin.AdminAccessEvaluator;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminGrants;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminOperationCatalog;
import ru.ludwigandreas.security.admin.AdminOutcome;
import ru.ludwigandreas.security.admin.AdminSurfaceGuard;
import ru.ludwigandreas.security.principal.Credential;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

class AdminAuditTest {

    private final List<AuditEvent> recorded = new ArrayList<>();
    private final AdminAudit audit = new AdminAudit(recorded::add);
    private final AdminOperationCatalog catalog =
            new AdminOperationCatalog(List.of(AdminOperationCatalogTest.area("outbox")));
    private final AdminSurfaceGuard guard = new AdminSurfaceGuard(catalog, new AdminAccessEvaluator(
            new AdminGrants(Map.of("outbox.operate", List.of("NOTIFICATION_ADMIN")), catalog),
            new org.springframework.security.authorization.AuthorizationEventPublisher() {
                @Override
                public <T> void publishAuthorizationEvent(
                        java.util.function.Supplier<org.springframework.security.core.Authentication> authentication,
                        T object, org.springframework.security.authorization.AuthorizationDecision decision) {
                }
            }), false);

    private MockHttpServletRequest request;

    @AfterEach
    void clear() {
        if (request != null) {
            guard.afterCompletion(request, null, null, null);
        }
        SecurityContextHolder.clearContext();
    }

    private void invoke(PrincipalType type, String reason) throws Exception {
        LudwigPrincipal principal = LudwigPrincipal.builder().subject(type == PrincipalType.USER ? "alice" : "ops-sa")
                .type(type).displayName("Alice").role("NOTIFICATION_ADMIN").build();
        SecurityContextHolder.getContext().setAuthentication(new LudwigAuthentication(principal, Credential.DIRECT));
        request = new MockHttpServletRequest("POST", "/admin/v1/outbox/messages/m1/retry");
        if (reason != null) {
            request.addHeader(AdminInvocation.REASON_HEADER, reason);
        }
        guard.preHandle(request, new MockHttpServletResponse(), new HandlerMethod(new AdminSurfaceGuardTest.Fixture(),
                AdminSurfaceGuardTest.Fixture.class.getMethod("operate")));
    }

    @Test
    @DisplayName("an operator's retry records actor, type, operation, target, reason and success - and no contents")
    void recordsAUserChange() throws Exception {
        invoke(PrincipalType.USER, "INC-4411");

        audit.record("outbox-message", "m1", AdminOutcome.SUCCEEDED);

        AuditEvent event = recorded.get(0);
        assertThat(event.category()).isEqualTo(AdminAudit.CATEGORY);
        assertThat(event.action()).isEqualTo("outbox.operate");
        assertThat(event.actor().subject()).isEqualTo("alice");
        assertThat(event.actor().principalType()).isEqualTo("USER");
        assertThat(event.resource().type()).isEqualTo("outbox-message");
        assertThat(event.resource().id()).isEqualTo("m1");
        assertThat(event.outcome().status()).isEqualTo(AuditOutcome.Status.SUCCESS);
        assertThat(event.attributes()).containsExactly(Map.entry("reason", "INC-4411"));
    }

    @Test
    @DisplayName("a service principal's change without a reason records the service and no reason")
    void recordsAServiceChange() throws Exception {
        invoke(PrincipalType.SERVICE, null);

        audit.record("outbox-message", "m1", AdminOutcome.SUCCEEDED, Map.of("moved", 3));

        AuditEvent event = recorded.get(0);
        assertThat(event.actor().subject()).isEqualTo("ops-sa");
        assertThat(event.actor().principalType()).isEqualTo("SERVICE");
        assertThat(event.attributes()).containsExactly(Map.entry("moved", 3));
    }

    @Test
    @DisplayName("refused-by-state and failure are distinct outcomes")
    void outcomes() throws Exception {
        invoke(PrincipalType.USER, null);

        audit.record("outbox-message", "m1", AdminOutcome.REFUSED_BY_STATE);
        audit.record("outbox-message", "m1", AdminOutcome.FAILED);

        assertThat(recorded).extracting(event -> event.outcome().status())
                .containsExactly(AuditOutcome.Status.DENIED, AuditOutcome.Status.FAILURE);
    }

    @Test
    @DisplayName("recording outside an authorized administrative request is a programming error")
    void requiresAnInvocation() {
        assertThatThrownBy(() -> audit.record("outbox-message", "m1", AdminOutcome.SUCCEEDED))
                .isInstanceOf(IllegalStateException.class);
    }
}
