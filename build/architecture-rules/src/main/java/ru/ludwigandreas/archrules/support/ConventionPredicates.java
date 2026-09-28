package ru.ludwigandreas.archrules.support;

import java.util.ArrayList;
import java.util.List;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;

import ru.ludwigandreas.archrules.AnnotationRole;
import ru.ludwigandreas.archrules.ExternalLibrary;
import ru.ludwigandreas.archrules.PackageRole;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.TypeRole;

import static ru.ludwigandreas.archrules.support.ArchitecturePredicates.assignableToAny;
import static ru.ludwigandreas.archrules.support.ArchitecturePredicates.residingIn;

/**
 * The stereotypes of a service, recognised through the configured {@link RuleContext}: what counts
 * as a controller, an entity, a repository, a Kafka consumer, and so on.
 *
 * <p>Most stereotypes are recognised both by package and by annotation. A class in
 * {@code ..controller..} is a controller even without {@code @RestController} (it may be a base
 * class), and a {@code @RestController} is a controller even if somebody parked it in the wrong
 * package - which is exactly the case the rules need to catch.
 *
 * <p>Every package-based stereotype is additionally confined to the analysed base packages, and that
 * is not a detail: a package identifier like {@code ..persistence..} also matches
 * {@code jakarta.persistence}, and {@code ..web..} matches
 * {@code org.springframework.web.bind.annotation}. Without the restriction, a controller would count
 * as calling "another controller" merely for carrying {@code @RestController}, and JPA's own API
 * would count as this service's repository layer. Third-party technologies are matched by
 * {@link ru.ludwigandreas.archrules.ExternalLibrary} instead, which is deliberately not restricted.
 */
public final class ConventionPredicates {

    private ConventionPredicates() {
    }

    /** Classes belonging to the service under analysis, as opposed to any library it uses. */
    public static DescribedPredicate<JavaClass> ownCode(RuleContext context) {
        return residingIn(context.basePackageIdentifiers()).as("the service's own code " + context.basePackages());
    }

    /**
     * Classes carrying one of the role's annotations, on the class or through a meta-annotation.
     *
     * <p>Annotation types are excluded, and that exclusion is load-bearing: Spring's
     * {@code @RestController} is itself meta-annotated with {@code @Controller}, so without it the
     * annotation type would be recognised as a controller and every annotated class would read as
     * "depending on another controller".
     */
    public static DescribedPredicate<JavaClass> annotatedAs(RuleContext context, AnnotationRole role) {
        return ArchitecturePredicates.<JavaClass>annotatedWithAny(context.annotations(role))
                .and(DescribedPredicate.not(JavaClass.Predicates.ANNOTATIONS))
                .as("classes annotated as " + role.id());
    }

    /** Classes of the service that play the given package role. */
    public static DescribedPredicate<JavaClass> inRole(RuleContext context, PackageRole role) {
        return residingIn(context.packages(role)).and(ownCode(context))
                .as("the service's " + role.id() + " packages " + context.packages(role));
    }

    /** REST entry points: in a controller package, or carrying a controller annotation. */
    public static DescribedPredicate<JavaClass> controllers(RuleContext context) {
        return controllerPackageClasses(context)
                .or(annotatedAs(context, AnnotationRole.CONTROLLER))
                .as("controllers");
    }

    /**
     * Classes in the controller packages, excluding the request/response models that usually live
     * below them - those cross the boundary, they do not define it.
     */
    public static DescribedPredicate<JavaClass> controllerPackageClasses(RuleContext context) {
        return inRole(context, PackageRole.CONTROLLER)
                .and(DescribedPredicate.not(residingIn(context.packages(PackageRole.DTO))))
                .and(DescribedPredicate.not(residingIn(context.packages(PackageRole.MAPPER))))
                .as("classes in the controller packages " + context.packages(PackageRole.CONTROLLER));
    }

    /** Service layer classes. */
    public static DescribedPredicate<JavaClass> services(RuleContext context) {
        return inRole(context, PackageRole.SERVICE).as("service layer classes");
    }

    /** JPA entities: annotated as persistent, or living in an entity package. */
    public static DescribedPredicate<JavaClass> entities(RuleContext context) {
        return annotatedAs(context, AnnotationRole.PERSISTENT_TYPE)
                .or(inRole(context, PackageRole.ENTITY))
                .as("JPA entities");
    }

    /** Spring Data repositories and hand-written data access components. */
    public static DescribedPredicate<JavaClass> repositories(RuleContext context) {
        return assignableToAny(context.types(TypeRole.SPRING_DATA_REPOSITORY))
                .or(annotatedAs(context, AnnotationRole.REPOSITORY))
                .or(inRole(context, PackageRole.REPOSITORY))
                .as("repositories");
    }

    /** Spring Data repository interfaces, recognised by type only. */
    public static DescribedPredicate<JavaClass> springDataRepositories(RuleContext context) {
        return assignableToAny(context.types(TypeRole.SPRING_DATA_REPOSITORY))
                .as("Spring Data repositories");
    }

    /** The service's own repository and entity packages, as a single predicate. */
    public static DescribedPredicate<JavaClass> persistencePackageClasses(RuleContext context) {
        return inRole(context, PackageRole.REPOSITORY)
                .or(inRole(context, PackageRole.ENTITY))
                .as("the service's persistence packages " + context.packages(PackageRole.REPOSITORY)
                        + " and " + context.packages(PackageRole.ENTITY));
    }

    /** {@code EntityManager}, Hibernate {@code Session}, {@code JdbcTemplate}, {@code DataSource}. */
    public static DescribedPredicate<JavaClass> persistenceAccessTypes(RuleContext context) {
        List<String> typeNames = new ArrayList<>(context.types(TypeRole.PERSISTENCE_CONTEXT));
        typeNames.addAll(context.types(TypeRole.JDBC_ACCESS));
        return assignableToAny(typeNames).as("persistence access types " + typeNames);
    }

    /** Anything belonging to the JPA/Hibernate API, annotations included. */
    public static DescribedPredicate<JavaClass> persistenceApi(RuleContext context) {
        return residingIn(context.libraryPackages(ExternalLibrary.PERSISTENCE_API))
                .as("the persistence API " + context.libraryPackages(ExternalLibrary.PERSISTENCE_API));
    }

    /** Classes in the messaging packages. */
    public static DescribedPredicate<JavaClass> messagingClasses(RuleContext context) {
        return inRole(context, PackageRole.MESSAGING).as("messaging classes");
    }

    /**
     * The Kafka API itself - the client library and Spring Kafka. A class <em>using</em> Kafka is a
     * class that depends on one of these, which is how the confinement rule is phrased.
     */
    public static DescribedPredicate<JavaClass> kafkaApi(RuleContext context) {
        return residingIn(context.libraryPackages(ExternalLibrary.KAFKA))
                .or(assignableToAny(context.types(TypeRole.KAFKA_CLIENT)))
                .as("the Kafka API " + context.libraryPackages(ExternalLibrary.KAFKA));
    }

    /** Kafka consumers, recognised by a {@code @KafkaListener} method or class annotation. */
    public static DescribedPredicate<JavaClass> kafkaConsumers(RuleContext context) {
        return ArchitecturePredicates.annotatedOnClassOrMemberWithAny(
                        context.annotations(AnnotationRole.KAFKA_LISTENER))
                .as("Kafka consumers");
    }

    /** Message/event payload classes. */
    public static DescribedPredicate<JavaClass> eventPayloads(RuleContext context) {
        return inRole(context, PackageRole.EVENT_PAYLOAD).as("Kafka payload classes");
    }

    /** Request/response models of the REST API. */
    public static DescribedPredicate<JavaClass> dtos(RuleContext context) {
        return inRole(context, PackageRole.DTO).as("REST DTOs");
    }

    /** Spring configuration classes: annotated as such, or living in a configuration package. */
    public static DescribedPredicate<JavaClass> configurationClasses(RuleContext context) {
        return annotatedAs(context, AnnotationRole.CONFIGURATION)
                .or(inRole(context, PackageRole.CONFIGURATION))
                .as("configuration classes");
    }

    /** The Spring Boot application class, which lives in the root package by convention. */
    public static DescribedPredicate<JavaClass> applicationClasses(RuleContext context) {
        return annotatedAs(context, AnnotationRole.APPLICATION).as("the application class");
    }

    /** The adapters that are allowed to speak to external object storage. */
    public static DescribedPredicate<JavaClass> storageAdapters(RuleContext context) {
        return inRole(context, PackageRole.STORAGE).as("storage adapters");
    }

    /** The object storage SDK. */
    public static DescribedPredicate<JavaClass> storageSdk(RuleContext context) {
        return residingIn(context.libraryPackages(ExternalLibrary.AWS_SDK))
                .as("the object storage SDK " + context.libraryPackages(ExternalLibrary.AWS_SDK));
    }
}
