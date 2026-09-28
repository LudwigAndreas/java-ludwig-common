package ru.ludwigandreas.archrules.rules;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.AnnotationRole;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.TypeRole;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * One exception hierarchy, and one place that translates it.
 *
 * <p>A shared base class is what lets the whole estate be handled uniformly: a single
 * {@code @ControllerAdvice} mapping, one place where an error code and an HTTP status are decided,
 * one type to catch at a service boundary. Exceptions that extend {@code RuntimeException} directly
 * are invisible to all of that and end up handled ad hoc - or not at all.
 *
 * <p>The catch rules are the other half. An entry point that catches a checked exception has taken
 * the translation decision locally, usually by logging and returning something plausible, which is
 * how a failed write becomes a 200. Entry points let exceptions out; the advice class turns them into
 * responses.
 */
public final class ExceptionRules implements ArchitectureRuleSet {

    /** Every custom exception extends the configured base exception. */
    public static final RuleId EXCEPTIONS_EXTEND_BASE =
            RuleId.of(RuleGroup.EXCEPTIONS, "custom-exceptions-extend-the-base-exception");

    /** Controllers do not translate exceptions themselves. */
    public static final RuleId CONTROLLERS_DO_NOT_CATCH_CHECKED =
            RuleId.of(RuleGroup.EXCEPTIONS, "controllers-do-not-catch-checked-exceptions");

    /** Neither do Kafka listeners. */
    public static final RuleId LISTENERS_DO_NOT_CATCH_CHECKED =
            RuleId.of(RuleGroup.EXCEPTIONS, "kafka-listeners-do-not-catch-checked-exceptions");

    private static final String BASE_EXCEPTION_PROPERTY = "architecture.rules.conventions.types.base-exception";

    @Override
    public RuleGroup group() {
        return RuleGroup.EXCEPTIONS;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        List<ArchitectureRule> rules = new ArrayList<>();
        rules.add(ArchitectureRule.of(EXCEPTIONS_EXTEND_BASE, exceptionsExtendBase(context),
                "Make the exception extend the shared base exception (" + describeBase(context) + "),"
                        + " so that the central @ControllerAdvice can map it to a response and an error code"
                        + " instead of it falling through as an unhandled runtime exception."));
        rules.add(ArchitectureRule.of(CONTROLLERS_DO_NOT_CATCH_CHECKED, controllersDoNotCatchChecked(context),
                "Remove the catch block and let the exception propagate; translate it in a class annotated"
                        + " @ControllerAdvice/@RestControllerAdvice. If the controller needs to add context,"
                        + " wrap the cause in an exception of the shared hierarchy and rethrow it."));
        rules.add(ArchitectureRule.of(LISTENERS_DO_NOT_CATCH_CHECKED, listenersDoNotCatchChecked(context),
                "Let the exception propagate out of the @KafkaListener method so the container's error"
                        + " handler can retry it or route it to the dead-letter topic; catching it here"
                        + " acknowledges the message as processed."));
        return List.copyOf(rules);
    }

    /**
     * Custom exceptions are the service's own throwables. The base class itself passes trivially
     * (it is assignable to itself), and so does any exception extending another one that extends it.
     */
    private static ArchRule exceptionsExtendBase(RuleContext context) {
        DescribedPredicate<JavaClass> customExceptions = ConventionPredicates.ownCode(context)
                .and(ArchitecturePredicates.assignableToAny(List.of(Throwable.class.getName())))
                .as("the service's own exceptions");
        return RequiredConfiguration.mustExtendConfiguredType(context, TypeRole.BASE_EXCEPTION, customExceptions,
                "Custom exceptions", BASE_EXCEPTION_PROPERTY, "com.acme.common.ApplicationException",
                EXCEPTIONS_EXTEND_BASE.value());
    }

    private static ArchRule controllersDoNotCatchChecked(RuleContext context) {
        return ArchRuleDefinition.codeUnits()
                .that().areDeclaredInClassesThat(ConventionPredicates.controllers(context)
                        // an advice class is a controller-ish stereotype but is exactly where
                        // translation is supposed to happen
                        .and(DescribedPredicate.not(ConventionPredicates
                                .annotatedAs(context, AnnotationRole.CONTROLLER_ADVICE))))
                .should(ArchitectureConditions.notCatchCheckedExceptions())
                .as("Controllers do not catch checked exceptions");
    }

    private static ArchRule listenersDoNotCatchChecked(RuleContext context) {
        return ArchRuleDefinition.methods()
                .that(ArchitecturePredicates.<com.tngtech.archunit.core.domain.JavaMethod>annotatedWithAny(
                                context.annotations(AnnotationRole.KAFKA_LISTENER))
                        .as("@KafkaListener methods"))
                .should(ArchitectureConditions.notCatchCheckedExceptions())
                .as("Kafka listeners do not catch checked exceptions");
    }

    private static String describeBase(RuleContext context) {
        return context.hasTypesFor(TypeRole.BASE_EXCEPTION)
                ? String.join(" or ", context.types(TypeRole.BASE_EXCEPTION))
                : "configure it with " + BASE_EXCEPTION_PROPERTY;
    }
}
