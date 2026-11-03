package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationEventPublisher;
import org.springframework.security.core.Authentication;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.cache.config.LudwigCacheAutoConfiguration;
import ru.ludwigandreas.security.config.AdminAutoConfiguration;
import ru.ludwigandreas.security.config.DataAuthorizationAutoConfiguration;
import ru.ludwigandreas.security.config.LudwigSecurityAutoConfiguration;
import ru.ludwigandreas.security.config.MutualTlsAutoConfiguration;
import ru.ludwigandreas.security.config.OperationAutoConfiguration;
import ru.ludwigandreas.security.config.ResourceServerAutoConfiguration;
import ru.ludwigandreas.security.config.SecurityAuditAutoConfiguration;
import ru.ludwigandreas.security.config.SecurityMetricsAutoConfiguration;
import ru.ludwigandreas.security.operation.Operation;
import ru.ludwigandreas.security.operation.OperationAuthority;
import ru.ludwigandreas.security.operation.OperationEvaluator;
import ru.ludwigandreas.security.operation.ReasonRequiredException;
import ru.ludwigandreas.security.operation.Sensitivity;
import ru.ludwigandreas.security.principal.Credential;
import ru.ludwigandreas.security.principal.LudwigAuthentication;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.security.principal.PrincipalType;
import ru.ludwigandreas.security.web.PatCredentialNotPermittedException;

/**
 * The security starter on a classpath without web-core - which this test is only ever run on: the module's
 * {@code without-web-core} Surefire execution removes web-core from the test classpath. Hiding classes from
 * conditions ({@code FilteredClassLoader}) would not do: the auto-configuration classes are linked by the application
 * classloader, which would still see web-core.
 */
class SecurityWithoutWebCoreTest {

    private static final Operation PURGE = Operation.business("sales.order.purge", Sensitivity.PRIVILEGED, "k");

    @Test
    @DisplayName("web-core really is absent from this test's classpath")
    void webCoreIsAbsent() {
        assertThatThrownBy(() -> Class.forName("ru.ludwigandreas.webcore.problem.LocalizedException"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    @DisplayName("every security auto-configuration starts without web-core")
    void startsWithoutWebCore() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(LudwigCacheAutoConfiguration.class,
                        LudwigSecurityAutoConfiguration.class, SecurityAuditAutoConfiguration.class,
                        SecurityMetricsAutoConfiguration.class, OperationAutoConfiguration.class,
                        DataAuthorizationAutoConfiguration.class, MutualTlsAutoConfiguration.class,
                        ResourceServerAutoConfiguration.class, AdminAutoConfiguration.class))
                .withBean(AuditSink.class, () -> event -> { })
                .withPropertyValues("ludwig.security.jwt.enabled=false")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(OperationEvaluator.class));
    }

    @Test
    @DisplayName("a privileged call without a reason, or with a personal access token, is refused with the security"
            + " starter's own refusal")
    void refusesWithoutWebCore() {
        OperationAuthority authority = TestOperations.authority(List.of(() -> List.of(PURGE)),
                Map.of("ADMIN", List.of(PURGE.id())));
        AuthorizationEventPublisher publisher = new AuthorizationEventPublisher() {
            @Override
            public <T> void publishAuthorizationEvent(Supplier<Authentication> authentication, T object,
                                                      AuthorizationDecision decision) {
            }
        };
        OperationEvaluator evaluator = TestOperations.evaluator(authority, publisher, false);
        LudwigPrincipal admin = LudwigPrincipal.builder().subject("alice").type(PrincipalType.USER)
                .roles(List.of("ADMIN")).build();

        assertThatThrownBy(() -> evaluator.authorize(new LudwigAuthentication(admin, Credential.DIRECT),
                admin, PURGE, null, "target"))
                .isInstanceOfSatisfying(ReasonRequiredException.class,
                        refusal -> assertThat(refusal.code()).isEqualTo(ReasonRequiredException.CODE));
        assertThatThrownBy(() -> evaluator.authorize(new LudwigAuthentication(admin,
                Credential.personalAccessToken("p1")), admin, PURGE, "INC-1", "target"))
                .isInstanceOfSatisfying(PatCredentialNotPermittedException.class,
                        refusal -> assertThat(refusal.code()).isEqualTo("ludwig.pat.credential-not-permitted"));
    }
}
