package ru.ludwigandreas.archrules.rules;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;

import ru.ludwigandreas.archrules.AnnotationRole;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.PackageRole;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ArchitecturePredicates;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * How the application context is put together: where bean definitions live, how dependencies get
 * in, and where a transaction may begin.
 *
 * <p>Field injection is here rather than in a style checker because it is a dependency question, not
 * a formatting one: a field-injected bean cannot be constructed in a test without a container,
 * cannot be made final, and hides how many collaborators a class has - the constructor is the one
 * place where "this class needs seven things" is impossible to miss. (A service that considers this
 * Checkstyle's job disables {@code spring.beans-use-constructor-injection} and loses nothing else.)
 *
 * <p>Transaction demarcation is confined to the service layer for a sharper reason:
 * {@code @Transactional} on a controller stretches the transaction across serialisation, and on a
 * repository it either does nothing useful or silently starts a second one. The service method is
 * the only place where the unit of work and the business operation are the same thing.
 */
public final class SpringWiringRules implements ArchitectureRuleSet {

    /** {@code @Configuration} classes reside in the configuration packages. */
    public static final RuleId CONFIGURATION_IN_CONFIG_PACKAGES =
            RuleId.of(RuleGroup.SPRING_WIRING, "configuration-classes-reside-in-config-packages");

    /** Beans receive their collaborators through the constructor. */
    public static final RuleId CONSTRUCTOR_INJECTION =
            RuleId.of(RuleGroup.SPRING_WIRING, "beans-use-constructor-injection");

    /**
     * Singleton beans have no mutable instance state. Covers {@code @Configuration} and the advice
     * classes as well, which are as singleton-scoped as a {@code @Service}.
     */
    public static final RuleId SINGLETON_BEANS_ARE_STATELESS =
            RuleId.of(RuleGroup.SPRING_WIRING, "singleton-beans-have-no-mutable-state");

    /** {@code @Transactional} classes are in the service layer. */
    public static final RuleId TRANSACTIONAL_CLASSES =
            RuleId.of(RuleGroup.SPRING_WIRING, "transactional-classes-are-in-the-service-layer");

    /** {@code @Transactional} methods are declared in the service layer. */
    public static final RuleId TRANSACTIONAL_METHODS =
            RuleId.of(RuleGroup.SPRING_WIRING, "transactional-methods-are-in-the-service-layer");

    @Override
    public RuleGroup group() {
        return RuleGroup.SPRING_WIRING;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        List<ArchitectureRule> rules = new ArrayList<>();
        if (context.hasPackagesFor(PackageRole.CONFIGURATION)) {
            rules.add(ArchitectureRule.of(CONFIGURATION_IN_CONFIG_PACKAGES, configurationInConfigPackages(context),
                    "Move the @Configuration class into the configuration package, so every bean definition"
                            + " the service makes is in one place instead of scattered through the layers."));
        }
        rules.add(ArchitectureRule.of(CONSTRUCTOR_INJECTION, constructorInjection(context),
                "Take the dependency as a constructor parameter and keep the field final"
                        + " (@RequiredArgsConstructor generates the constructor); field injection hides how"
                        + " many collaborators the class has and makes it unconstructible in a plain unit test."));
        rules.add(ArchitectureRule.of(SINGLETON_BEANS_ARE_STATELESS, singletonBeansAreStateless(context),
                "Make the field final and set it in the constructor. If it really is per-request state,"
                        + " it belongs in a method parameter or a local variable - a singleton bean is shared"
                        + " by every concurrent request, so a mutable field is shared mutable state."));
        if (context.hasPackagesFor(PackageRole.TRANSACTIONAL_HOST)) {
            rules.add(ArchitectureRule.of(TRANSACTIONAL_CLASSES, transactionalClasses(context),
                    "Move the @Transactional annotation onto the service class that owns the unit of work."
                            + " On a controller the transaction stretches across serialisation; on a repository"
                            + " it either does nothing or silently opens a second one."));
            rules.add(ArchitectureRule.of(TRANSACTIONAL_METHODS, transactionalMethods(context),
                    "Move the transactional work into a service method and annotate that. The service method"
                            + " is the only place where the unit of work and the business operation coincide."));
        }
        return List.copyOf(rules);
    }

    private static ArchRule configurationInConfigPackages(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.annotatedAs(context, AnnotationRole.CONFIGURATION)
                        // @SpringBootApplication is a @Configuration by meta-annotation and belongs
                        // in the root package - it is the one bean definition that must not move.
                        .and(DescribedPredicate.not(ConventionPredicates.applicationClasses(context)))
                        .as("configuration classes " + context.annotations(AnnotationRole.CONFIGURATION)))
                .should().resideInAnyPackage(context.packageArray(PackageRole.CONFIGURATION))
                .as("Configuration classes reside in the configuration packages");
    }

    private static ArchRule constructorInjection(RuleContext context) {
        return ArchRuleDefinition.fields()
                .should(ArchitectureConditions.<JavaField>membersNotAnnotatedWithAny(
                        context.annotations(AnnotationRole.FIELD_INJECTION),
                        "an injection annotation - inject through the constructor instead"))
                .as("Beans use constructor injection");
    }

    /**
     * The stereotypes checked here are configurable ({@link AnnotationRole#SINGLETON_BEAN}) and
     * include {@code @Configuration}, {@code @ControllerAdvice} and {@code @RestControllerAdvice}
     * alongside the obvious {@code @Service}/{@code @Component}/{@code @RestController}: Spring keeps
     * exactly one instance of each of them, so mutable state on any of them is shared across every
     * request that touches it.
     */
    private static ArchRule singletonBeansAreStateless(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.annotatedAs(context, AnnotationRole.SINGLETON_BEAN)
                        .as("singleton beans " + context.annotations(AnnotationRole.SINGLETON_BEAN)))
                .should(ArchitectureConditions.notDeclareNonFinalInstanceFields())
                .as("Singleton beans have no mutable instance state");
    }

    private static ArchRule transactionalClasses(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(ConventionPredicates.annotatedAs(context, AnnotationRole.TRANSACTIONAL)
                        .as("@Transactional classes"))
                .should().resideInAnyPackage(context.packageArray(PackageRole.TRANSACTIONAL_HOST))
                .as("Transactional classes are in the service layer");
    }

    private static ArchRule transactionalMethods(RuleContext context) {
        return ArchRuleDefinition.methods()
                .that(ArchitecturePredicates.<JavaMethod>annotatedWithAny(
                                context.annotations(AnnotationRole.TRANSACTIONAL))
                        .as("@Transactional methods"))
                .should().beDeclaredInClassesThat()
                .resideInAnyPackage(context.packageArray(PackageRole.TRANSACTIONAL_HOST))
                .as("Transactional methods are in the service layer");
    }
}
