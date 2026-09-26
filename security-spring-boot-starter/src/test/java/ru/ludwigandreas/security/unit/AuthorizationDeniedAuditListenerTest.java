package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.event.AuthorizationDeniedEvent;
import org.springframework.security.core.Authentication;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.security.audit.AuthorizationDeniedAuditListener;
import ru.ludwigandreas.security.metrics.SecurityMetrics;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthorizationDeniedAuditListenerTest {

    interface OrderService {
        void approve();
    }

    @Mock
    private AuditSink auditSink;

    @Mock
    private SecurityMetrics metrics;

    @Mock
    private MethodInvocation invocation;

    private AuthorizationDeniedAuditListener listener() {
        return new AuthorizationDeniedAuditListener(auditSink, metrics);
    }

    private Method approve() throws NoSuchMethodException {
        return OrderService.class.getMethod("approve");
    }

    private Authentication alice() {
        return new LudwigAuthentication(LudwigPrincipal.builder()
                .subject("alice")
                .type(PrincipalType.USER)
                .build());
    }

    /**
     * The hole this closes: a caller refused for lacking a role produced a 403 and no record anywhere,
     * so "why can't this user do X" and "is anyone probing this endpoint" both had no answer.
     */
    @Test
    @DisplayName("a @PreAuthorize denial reaches the audit trail and the counters")
    void recordsAMethodSecurityDenial() throws Exception {
        org.mockito.Mockito.when(invocation.getMethod()).thenReturn(approve());
        Authentication authentication = alice();

        listener().onApplicationEvent(new AuthorizationDeniedEvent<>(
                () -> authentication, invocation, new AuthorizationDecision(false)));

        ArgumentCaptor<AuditEvent> recorded = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditSink).record(recorded.capture());
        AuditEvent event = recorded.getValue();
        assertThat(event.outcome().status()).isEqualTo(AuditOutcome.Status.DENIED);
        assertThat(event.category()).isEqualTo("access");
        assertThat(event.action()).isEqualTo("access.denied");
        assertThat(event.actor().subject()).isEqualTo("alice");
        assertThat(event.actor().principalType()).isEqualTo(PrincipalType.USER.name());
        assertThat(event.resource().type()).isEqualTo("OrderService");
        assertThat(event.attributes()).containsEntry("scopeAccess", "N/A");
        assertThat(event.outcome().reason()).isEqualTo("insufficient-authority");
        verify(metrics).recordAccessDenied("OrderService", "approve");
    }

    /**
     * An exception escaping here would propagate into the authorization decision that published the
     * event - audit code must never be able to change the outcome it is observing.
     */
    @Test
    @DisplayName("a denial with no resolvable authentication is still recorded, and never rethrows")
    void survivesAnUnresolvableAuthentication() throws Exception {
        org.mockito.Mockito.when(invocation.getMethod()).thenReturn(approve());

        assertThatCode(() -> listener().onApplicationEvent(new AuthorizationDeniedEvent<>(
                () -> {
                    throw new IllegalStateException("no authentication");
                },
                invocation,
                new AuthorizationDecision(false))))
                .doesNotThrowAnyException();

        verify(auditSink).record(org.mockito.ArgumentMatchers.any(AuditEvent.class));
    }

    @Test
    @DisplayName("a decision that is not a method invocation is recorded without one rather than dropped")
    void handlesNonMethodObjects() {
        Authentication authentication = alice();

        listener().onApplicationEvent(new AuthorizationDeniedEvent<>(
                () -> authentication, "some-request", new AuthorizationDecision(false)));

        ArgumentCaptor<AuditEvent> recorded = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditSink).record(recorded.capture());
        assertThat(recorded.getValue().resource().type()).isEqualTo("unknown");
    }
}
