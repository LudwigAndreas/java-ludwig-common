package ru.ludwigandreas.archrules.support;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;

/**
 * Predicates the built-in rule sets are written in terms of, published so that a service's own
 * {@link ru.ludwigandreas.archrules.ArchitectureRuleSet} can be written the same way.
 *
 * <p>All of them take fully qualified names rather than {@code Class} literals, because this library
 * does not put Spring, JPA, Kafka or the AWS SDK on anybody's classpath, and all of them are
 * null-safe about empty configuration: a predicate over an empty set of packages matches nothing
 * instead of throwing, so a convention a service cleared simply switches its rules off rather than
 * breaking the build in a confusing way.
 */
public final class ArchitecturePredicates {

    private ArchitecturePredicates() {
    }

    /** Classes residing in any of the given ArchUnit package identifiers. */
    public static DescribedPredicate<JavaClass> residingIn(Collection<String> packageIdentifiers) {
        List<String> identifiers = copy(packageIdentifiers);
        if (identifiers.isEmpty()) {
            return DescribedPredicate.<JavaClass>alwaysFalse().as("reside in no package (none configured)");
        }
        return JavaClass.Predicates.resideInAnyPackage(identifiers.toArray(String[]::new));
    }

    /** Classes residing outside every one of the given ArchUnit package identifiers. */
    public static DescribedPredicate<JavaClass> residingOutsideOf(Collection<String> packageIdentifiers) {
        List<String> identifiers = copy(packageIdentifiers);
        if (identifiers.isEmpty()) {
            return DescribedPredicate.<JavaClass>alwaysTrue().as("reside outside of no package (none configured)");
        }
        return JavaClass.Predicates.resideOutsideOfPackages(identifiers.toArray(String[]::new));
    }

    /**
     * Classes assignable to any of the given types, by name. Catches the whole hierarchy, so
     * {@code org.springframework.data.repository.Repository} also matches a service's own
     * {@code JpaRepository} sub-interface.
     */
    public static DescribedPredicate<JavaClass> assignableToAny(Collection<String> typeNames) {
        List<String> names = copy(typeNames);
        if (names.isEmpty()) {
            return DescribedPredicate.<JavaClass>alwaysFalse().as("be assignable to no type (none configured)");
        }
        return DescribedPredicate.describe("assignable to any of " + names,
                javaClass -> names.stream().anyMatch(name -> javaClass.isAssignableTo(name)));
    }

    /** Classes or members carrying any of the given annotations, meta-annotations included. */
    public static <T extends CanBeAnnotated> DescribedPredicate<T> annotatedWithAny(Collection<String> annotationNames) {
        List<String> names = copy(annotationNames);
        if (names.isEmpty()) {
            return DescribedPredicate.<T>alwaysFalse().as("annotated with no annotation (none configured)");
        }
        return DescribedPredicate.describe("annotated with any of " + names,
                annotated -> names.stream()
                        .anyMatch(name -> annotated.isAnnotatedWith(name) || annotated.isMetaAnnotatedWith(name)));
    }

    /**
     * Classes that declare at least one member carrying any of the given annotations - how a Kafka
     * consumer is recognised, since {@code @KafkaListener} sits on the method, not on the class.
     */
    public static DescribedPredicate<JavaClass> declaringMemberAnnotatedWithAny(Collection<String> annotationNames) {
        List<String> names = copy(annotationNames);
        if (names.isEmpty()) {
            return DescribedPredicate.<JavaClass>alwaysFalse().as("declare no annotated member (none configured)");
        }
        DescribedPredicate<JavaMember> annotatedMember = annotatedWithAny(names);
        return DescribedPredicate.describe("declaring a member annotated with any of " + names,
                javaClass -> javaClass.getMembers().stream().anyMatch(annotatedMember));
    }

    /**
     * Classes annotated with any of the given annotations themselves, or declaring a member that is.
     * Used wherever a stereotype may be expressed on either level.
     */
    public static DescribedPredicate<JavaClass> annotatedOnClassOrMemberWithAny(Collection<String> annotationNames) {
        List<String> names = copy(annotationNames);
        if (names.isEmpty()) {
            return DescribedPredicate.<JavaClass>alwaysFalse().as("carry no annotation (none configured)");
        }
        DescribedPredicate<JavaClass> onClass = annotatedWithAny(names);
        return onClass.or(declaringMemberAnnotatedWithAny(names))
                .as("annotated on class or member with any of " + names);
    }

    /** Classes belonging to any of the given module packages, sub-packages included. */
    public static DescribedPredicate<JavaClass> insideAnyOf(Collection<String> packageNames) {
        List<String> names = copy(packageNames);
        if (names.isEmpty()) {
            return DescribedPredicate.<JavaClass>alwaysFalse().as("inside no package (none configured)");
        }
        return residingIn(names.stream().map(name -> name + "..").toList())
                .as("inside any of " + names);
    }

    /**
     * Classes having at least one direct dependency on a class matching {@code target} - "classes
     * that use X", as opposed to "classes that are X".
     */
    public static DescribedPredicate<JavaClass> dependingOnClassesThat(DescribedPredicate<? super JavaClass> target,
                                                                       String description) {
        Objects.requireNonNull(target, "target");
        return DescribedPredicate.describe("depending on " + description, javaClass -> {
            for (com.tngtech.archunit.core.domain.Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                JavaClass targetClass = dependency.getTargetClass().getBaseComponentType();
                if (!targetClass.equals(javaClass) && target.test(targetClass)) {
                    return true;
                }
            }
            return false;
        });
    }

    private static List<String> copy(Collection<String> values) {
        return List.copyOf(Objects.requireNonNull(values, "values"));
    }
}
