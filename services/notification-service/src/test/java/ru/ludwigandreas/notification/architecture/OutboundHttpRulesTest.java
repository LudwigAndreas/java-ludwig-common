package ru.ludwigandreas.notification.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import java.net.http.HttpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import ru.ludwigandreas.notification.architecture.violations.RogueHttpClient;
import ru.ludwigandreas.notification.config.NotificationChannelConfig;

/**
 * An outbound HTTP client is built in one class, and that class's partners have stand-ins.
 *
 * <p>The platform's rule for partners is "every {@code @LudwigRestClient} has a stub", checked by
 * {@link LocalRunArchitectureTest}. This service has no such client: its chat and webhook channels
 * build a plain {@code RestClient} each, in {@link NotificationChannelConfig}, so that rule sees
 * nothing here. This is the equivalent for the shape this service actually has.
 *
 * <p>It does not, and cannot, check that a stub exists for each client built there - which client a
 * {@code RestClient} is for is not in the bytecode. What it does is make a new outbound partner
 * impossible to add anywhere except the one class a reviewer reads beside {@code NotificationStubs}.
 * A local rule, like the in-app and announcement rules beside it, because the class it names is this
 * service's own.
 */
class OutboundHttpRulesTest {

    private static final String PRODUCTION = "ru.ludwigandreas.notification";

    @Test
    @DisplayName("only NotificationChannelConfig builds an outbound HTTP client")
    void outboundClientsAreBuiltInOnePlace() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PRODUCTION);

        rule().check(production);
    }

    @Test
    @DisplayName("the rule fails for a class that builds a client of its own, naming it")
    void aRogueClientIsReported() {
        JavaClasses rogue = new ClassFileImporter().importClasses(RogueHttpClient.class);

        EvaluationResult result = rule().evaluate(rogue);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains(RogueHttpClient.class.getName());
    }

    private static ArchRule rule() {
        return noClasses()
                .that().doNotHaveFullyQualifiedName(NotificationChannelConfig.class.getName())
                .should().callMethodWhere(buildsAnHttpClient())
                .because("a new outbound partner needs a stand-in in NotificationStubs, and "
                        + "NotificationChannelConfig is where a reviewer looks for partners. Build the "
                        + "client there");
    }

    private static DescribedPredicate<JavaMethodCall> buildsAnHttpClient() {
        return new DescribedPredicate<>("builds an outbound HTTP client") {
            @Override
            public boolean test(JavaMethodCall call) {
                String method = call.getName();
                if (call.getTargetOwner().isAssignableTo(RestClient.Builder.class)) {
                    return "build".equals(method);
                }
                if (call.getTargetOwner().isEquivalentTo(RestClient.class)) {
                    return "create".equals(method) || "builder".equals(method);
                }
                if (call.getTargetOwner().isEquivalentTo(HttpClient.class)) {
                    return "newHttpClient".equals(method) || "newBuilder".equals(method);
                }
                return false;
            }
        };
    }
}
