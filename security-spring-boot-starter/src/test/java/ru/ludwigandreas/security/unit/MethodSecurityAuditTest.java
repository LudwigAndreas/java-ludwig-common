package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import ru.ludwigandreas.audit.config.AuditCoreAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ludwigandreas.audit.AuditEvent;
import ru.ludwigandreas.audit.AuditOutcome;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.security.config.DataAuthorizationAutoConfiguration;
import ru.ludwigandreas.security.config.LudwigSecurityAutoConfiguration;
import ru.ludwigandreas.security.config.SecurityMetricsAutoConfiguration;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;

/**
 * Proves the wiring actually works rather than assuming it: that {@code @EnableMethodSecurity} picks up
 * the module's {@code AuthorizationEventPublisher}, that the event reaches the listener, and that a
 * role-level refusal therefore lands in the same audit trail as a row-level one.
 *
 * <p>Worth an end-to-end test because every link in that chain is implicit - a bean Spring Security
 * autowires optionally, an event type, a listener resolved by generic type. Any of them could stop
 * matching on an upgrade, and the symptom would be silence: denials still work, nothing records them.
 */
class MethodSecurityAuditTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    LudwigSecurityAutoConfiguration.class,
                    SecurityMetricsAutoConfiguration.class,
                    DataAuthorizationAutoConfiguration.class,
                    AuditCoreAutoConfiguration.class))
            .withUserConfiguration(GuardedServiceConfiguration.class);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String subject, String... roles) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new LudwigAuthentication(LudwigPrincipal.builder()
                .subject(subject)
                .type(PrincipalType.USER)
                .roles(Set.of(roles))
                .build()));
        SecurityContextHolder.setContext(context);
    }

    @Test
    @DisplayName("a role-level refusal is audited, naming the method that refused it")
    void auditsAPreAuthorizeDenial() {
        runner.run(context -> {
            RecordingAuditLogger audit = context.getBean(RecordingAuditLogger.class);
            GuardedService service = context.getBean(GuardedService.class);
            authenticateAs("alice", "ROLE_VIEWER");

            assertThatThrownBy(service::approve).isInstanceOf(AccessDeniedException.class);

            assertThat(audit.recorded()).hasSize(1);
            AuditEvent event = audit.recorded().get(0);
            assertThat(event.outcome().status()).isEqualTo(AuditOutcome.Status.DENIED);
            assertThat(event.actor().subject()).isEqualTo("alice");
            assertThat(event.resource().type()).isEqualTo("GuardedService");
            assertThat(event.action()).isEqualTo("access.denied");
        });
    }

    @Test
    @DisplayName("a permitted call records nothing - the trail is for refusals, not for traffic")
    void doesNotAuditAGrantedCall() {
        runner.run(context -> {
            RecordingAuditLogger audit = context.getBean(RecordingAuditLogger.class);
            GuardedService service = context.getBean(GuardedService.class);
            authenticateAs("alice", "ROLE_APPROVER");

            service.approve();

            assertThat(audit.recorded()).isEmpty();
        });
    }

    static class GuardedService {

        @PreAuthorize("hasRole('APPROVER')")
        public void approve() {
            // nothing to do - the annotation is the subject of the test
        }
    }

    static class RecordingAuditLogger implements AuditSink {

        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditEvent event) {
            events.add(event);
        }

        List<AuditEvent> recorded() {
            return events;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class GuardedServiceConfiguration {

        @Bean
        GuardedService guardedService() {
            return new GuardedService();
        }

        @Bean
        RecordingAuditLogger recordingAuditLogger() {
            return new RecordingAuditLogger();
        }
    }
}
