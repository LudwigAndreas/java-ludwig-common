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
import ru.ludwigandreas.security.audit.AccessAuditLogger;
import ru.ludwigandreas.security.audit.AccessDecision;
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
    private AccessAuditLogger auditLogger;

    @Mock
    private SecurityMetrics metrics;

    @Mock
    private MethodInvocation invocation;

    private AuthorizationDeniedAuditListener listener() {
        return new AuthorizationDeniedAuditListener(auditLogger, metrics);
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

        ArgumentCaptor<AccessDecision> recorded = ArgumentCaptor.forClass(AccessDecision.class);
        verify(auditLogger).record(recorded.capture());
        assertThat(recorded.getValue().granted()).isFalse();
        assertThat(recorded.getValue().subject()).isEqualTo("alice");
        assertThat(recorded.getValue().principalType()).isEqualTo(PrincipalType.USER);
        assertThat(recorded.getValue().resourceType()).isEqualTo("OrderService");
        assertThat(recorded.getValue().action()).isEqualTo("approve");
        assertThat(recorded.getValue().reason()).isEqualTo("insufficient-authority");
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

        verify(auditLogger).record(org.mockito.ArgumentMatchers.any(AccessDecision.class));
    }

    @Test
    @DisplayName("a decision that is not a method invocation is recorded without one rather than dropped")
    void handlesNonMethodObjects() {
        Authentication authentication = alice();

        listener().onApplicationEvent(new AuthorizationDeniedEvent<>(
                () -> authentication, "some-request", new AuthorizationDecision(false)));

        ArgumentCaptor<AccessDecision> recorded = ArgumentCaptor.forClass(AccessDecision.class);
        verify(auditLogger).record(recorded.capture());
        assertThat(recorded.getValue().resourceType()).isEqualTo("unknown");
    }
}
