package ru.ludwigandreas.notification.config;

import jakarta.mail.MessagingException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemDefinition;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Teaches the shared problem pipeline about the third-party exceptions this service can leak, and
 * registers its own message bundle.
 *
 * <p>This is the whole of this service's error handling. There is no {@code @RestControllerAdvice}
 * anywhere in the module - which is the point of {@code web-core-spring-boot-starter}: a module
 * states the <em>meaning</em> of a failure and the text comes from a bundle, in the caller's language,
 * rendered identically to every other error the service emits.
 *
 * <p>The mappers below exist for exceptions this service does not own and cannot subclass. Without
 * them a mail server that is briefly unreachable during a synchronous send would surface as a bare
 * 500 carrying a stack-trace-derived message - which tells the caller nothing actionable and leaks
 * the mail host's name.
 */
@Configuration(proxyBeanMethods = false)
public class NotificationProblemConfig {

    /**
     * This service's own error text.
     *
     * <p>Registered as a bundle rather than resolved directly, so the application's own
     * {@code messages.properties} can override any key without a fork - and so every module's errors
     * come out of one pipeline in one shape.
     */
    @Bean
    public ProblemMessageBundle notificationProblemMessages() {
        return ProblemMessageBundle.of("i18n/notification-messages");
    }

    /**
     * A mail server that could not be reached during a synchronous operation.
     *
     * <p>502 rather than 500: the failure is upstream, the request was fine, and the caller's correct
     * response is to retry rather than to change anything. Queued sends never reach this - they are
     * classified by the channel and retried - so this covers only the paths where a caller is waiting.
     */
    @Bean
    @ConditionalOnClass(MailException.class)
    public ExceptionProblemMapper mailExceptionProblemMapper() {
        return ExceptionProblemMapper.forType(MailException.class,
                exception -> ProblemDefinition.of(ProblemStatus.BAD_GATEWAY,
                        "error.notification.upstream.mail"));
    }

    @Bean
    @ConditionalOnClass(MessagingException.class)
    public ExceptionProblemMapper messagingExceptionProblemMapper() {
        return ExceptionProblemMapper.forType(MessagingException.class,
                exception -> ProblemDefinition.of(ProblemStatus.BAD_GATEWAY,
                        "error.notification.upstream.mail"));
    }

    /**
     * A provider HTTP call that timed out or could not connect.
     *
     * <p>504, and deliberately carrying no detail from the exception: its message contains the
     * provider's URL, which is internal topology and has no business in a response to an external
     * caller.
     */
    @Bean
    public ExceptionProblemMapper providerTimeoutProblemMapper() {
        return ExceptionProblemMapper.forType(ResourceAccessException.class,
                exception -> ProblemDefinition.of(ProblemStatus.GATEWAY_TIMEOUT,
                        "error.notification.upstream.timeout"));
    }

    /**
     * A provider that answered with an error status.
     *
     * <p>The upstream status is published as a machine-readable property so a caller can distinguish
     * "the provider rejected this" from "the provider is down", without the response body quoting
     * whatever the provider said - which may name a recipient.
     */
    @Bean
    public ExceptionProblemMapper providerResponseProblemMapper() {
        return ExceptionProblemMapper.forType(RestClientResponseException.class,
                exception -> ProblemDefinition.of(ProblemStatus.BAD_GATEWAY,
                                "error.notification.upstream.provider")
                        .withProperty("upstreamStatus", exception.getStatusCode().value()));
    }
}
