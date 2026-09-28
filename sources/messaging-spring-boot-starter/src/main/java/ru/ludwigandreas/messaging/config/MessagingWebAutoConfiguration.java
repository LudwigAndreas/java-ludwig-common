package ru.ludwigandreas.messaging.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import ru.ludwigandreas.messaging.error.MessagingProblemCodes;
import ru.ludwigandreas.messaging.error.UnsupportedEventVersionException;
import ru.ludwigandreas.messaging.settings.MessagingProperties;
import ru.ludwigandreas.webcore.problem.ExceptionProblemMapper;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * Teaches web-core's one {@code ProblemDetail} pipeline about this module's failures, if a service has
 * that pipeline at all.
 *
 * <p>Conditional on web-core being on the classpath, which for this module is the unusual case rather
 * than the expected one - a consumer's failures are answered by a dead-letter topic, not by an HTTP
 * response, and this starter keeps web-core optional so that {@code outbox-spring-boot-starter} does not
 * inherit it. What this configuration is for is the service that re-raises one of these from its own
 * surface: a replay endpoint that re-feeds a dead-lettered record and has to tell the operator why it will
 * not load, most obviously.
 *
 * <p>A contributed {@link ExceptionProblemMapper} and a contributed {@link ProblemMessageBundle}, not an
 * {@code @RestControllerAdvice}. That is the convention every starter in this repository follows, and
 * web-core states the reason: a module says only what its failure <em>means</em>, and the text comes from
 * the bundle in the caller's language, rendered identically to every other error the service emits.
 */
@AutoConfiguration
@ConditionalOnClass(ExceptionProblemMapper.class)
@ConditionalOnProperty(prefix = MessagingProperties.PREFIX, name = "enabled", matchIfMissing = true)
public class MessagingWebAutoConfiguration {

    /**
     * This module's error text.
     *
     * @return the bundle
     */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigMessagingProblemMessages")
    public ProblemMessageBundle ludwigMessagingProblemMessages() {
        return ProblemMessageBundle.of("i18n/ludwig-messaging-messages");
    }

    /**
     * Renders an unsupported event version as a 422.
     *
     * <p>422 rather than 400 or 409, for the reason {@code FingerprintMismatchException} gives for the same
     * status: the request was well-formed and addressed a valid resource, and its content cannot be acted
     * on. 409 would invite a retry, and this will produce the same answer forever - the version on a
     * record does not change.
     *
     * @return the mapper
     */
    @Bean
    @ConditionalOnMissingBean(name = "ludwigUnsupportedEventVersionProblemMapper")
    public ExceptionProblemMapper ludwigUnsupportedEventVersionProblemMapper() {
        return ExceptionProblemMapper.forType(UnsupportedEventVersionException.class,
                exception -> ru.ludwigandreas.webcore.problem.ProblemDefinition.of(
                                ProblemStatus.UNPROCESSABLE, MessagingProblemCodes.UNSUPPORTED_EVENT_VERSION,
                                exception.getEventVersion(), exception.getMinAccepted(),
                                exception.getMaxAccepted())
                        .withProperty("topic", exception.getTopic())
                        .withProperty("eventVersion", exception.getEventVersion())
                        .withProperty("minAcceptedVersion", exception.getMinAccepted())
                        .withProperty("maxAcceptedVersion", exception.getMaxAccepted()));
    }
}
