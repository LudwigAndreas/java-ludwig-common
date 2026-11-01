package ru.ludwigandreas.archrules.rules;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvent;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.util.List;
import java.util.Optional;
import ru.ludwigandreas.archrules.ArchitectureRule;
import ru.ludwigandreas.archrules.ArchitectureRuleSet;
import ru.ludwigandreas.archrules.RuleContext;
import ru.ludwigandreas.archrules.RuleGroup;
import ru.ludwigandreas.archrules.RuleId;
import ru.ludwigandreas.archrules.support.ArchitectureConditions;
import ru.ludwigandreas.archrules.support.ConventionPredicates;

/**
 * The administrative surface's structure: every handler under {@code /admin/} says what it does, through exactly
 * one gate, under the versioned prefix, without reaching a repository.
 *
 * <h2>The four rules</h2>
 *
 * <ol>
 *   <li><b>A handler under {@code /admin/} declares an operation.</b> {@code @RequiresOperation} names the
 *       operation; {@code @RequiresAuthentication} marks the rare handler that performs none. A handler with neither
 *       is also refused at runtime - this rule makes it a build failure rather than a {@code 403} somebody
 *       reports.</li>
 *   <li><b>No second gate.</b> A handler declaring an operation - on any surface - that also carries
 *       {@code @PreAuthorize}, {@code @Secured} or {@code @RolesAllowed}, on the method or on its class, has two
 *       places its authorization is decided, and the second one names a role, which code must never do: roles are
 *       defined only in the service's {@code ludwig.security.roles}.</li>
 *   <li><b>Mapped under {@code /admin/v1/}.</b> Everything under {@code /admin/} is versioned, as the guard and every
 *       administrative client expect. Whether an operation's surface agrees with its handler's path is a fact of the
 *       catalog, so the security starter checks it at startup.</li>
 *   <li><b>No repository behind an administrative controller.</b> The module service owns the transaction, the
 *       tenant predicate and the audit record, which is where the administrative change has to be recorded.</li>
 * </ol>
 *
 * <p>Paths are read from the mapping annotations on the class and the method. A placeholder
 * {@code ${key:default}} is read as its default; one without a default cannot be resolved from bytecode and the
 * method is skipped by the path-based rules rather than guessed at.
 *
 * <p>Annotations are matched by name, so that a module enabling these rules inherits neither Spring MVC nor the
 * security starter from them.
 *
 * <h2>What this group cannot see</h2>
 *
 * <ol>
 *   <li><b>That an operation id is catalogued.</b> The catalog is assembled from beans at runtime; the security
 *       starter fails startup on an uncatalogued id instead.</li>
 *   <li><b>That an operation's sensitivity matches what it does, and its surface its path.</b> The first is a
 *       judgement, reviewed against each module's README table of operations; the second is checked at startup.</li>
 *   <li><b>That a module's endpoints are absent without the security starter.</b> A property of the assembled
 *       context, tested by each declaring module's auto-configuration test.</li>
 * </ol>
 */
public final class AdminRules implements ArchitectureRuleSet {

    /** A handler under {@code /admin/} carries {@code @RequiresOperation} or {@code @RequiresAuthentication}. */
    public static final RuleId HANDLER_DECLARES_AN_OPERATION =
            RuleId.of(RuleGroup.ADMIN, "handler-declares-an-operation");

    /** A handler declaring an operation, and its class, carry no other authorization annotation. */
    public static final RuleId NO_SECOND_GATE = RuleId.of(RuleGroup.ADMIN, "no-second-gate");

    /** A handler under {@code /admin/} is mapped under {@code /admin/v1/}. */
    public static final RuleId MAPPED_UNDER_THE_VERSIONED_PREFIX =
            RuleId.of(RuleGroup.ADMIN, "mapped-under-the-versioned-prefix");

    /** An administrative controller delegates to a service, never to a repository. */
    public static final RuleId CONTROLLER_IS_A_THIN_ADAPTER =
            RuleId.of(RuleGroup.ADMIN, "controller-is-a-thin-adapter");

    /**
     * {@code @AdminObject} marks only a {@code @PathVariable}: the surface guard reads the object from the matched URI
     * template before the handler runs, so on any other parameter it would scope nothing.
     */
    public static final RuleId ADMIN_OBJECT_IS_A_PATH_VARIABLE =
            RuleId.of(RuleGroup.ADMIN, "admin-object-is-a-path-variable");

    private static final String ADMIN_OBJECT = "ru.ludwigandreas.security.admin.AdminObject";
    private static final String PATH_VARIABLE = "org.springframework.web.bind.annotation.PathVariable";

    private static final String REQUIRES_OPERATION = "ru.ludwigandreas.security.operation.RequiresOperation";
    private static final String REQUIRES_AUTHENTICATION = "ru.ludwigandreas.security.operation.RequiresAuthentication";
    private static final List<String> OPERATION_ANNOTATIONS = List.of(REQUIRES_OPERATION, REQUIRES_AUTHENTICATION);

    private static final String ADMIN_PREFIX = "/admin/";
    private static final String VERSIONED_PREFIX = "/admin/v1/";

    private static final String WEB = "org.springframework.web.bind.annotation.";
    private static final String CLASS_MAPPING = WEB + "RequestMapping";
    private static final List<String> METHOD_MAPPINGS = List.of(WEB + "RequestMapping", WEB + "GetMapping",
            WEB + "PostMapping", WEB + "PutMapping", WEB + "DeleteMapping", WEB + "PatchMapping");

    private static final List<String> OTHER_GATES = List.of(
            "org.springframework.security.access.prepost.PreAuthorize",
            "org.springframework.security.access.prepost.PostAuthorize",
            "org.springframework.security.access.annotation.Secured",
            "jakarta.annotation.security.RolesAllowed");

    @Override
    public RuleGroup group() {
        return RuleGroup.ADMIN;
    }

    @Override
    public List<ArchitectureRule> rules(RuleContext context) {
        return List.of(
                ArchitectureRule.of(HANDLER_DECLARES_AN_OPERATION, handlerDeclaresAnOperation(),
                        "Annotate the handler with @RequiresOperation(<Module>AdminOperations.<OPERATION>), naming the"
                                + " operation it performs, or with @RequiresAuthentication if it performs none. A"
                                + " handler under /admin/ with neither is refused by the surface guard at runtime."),
                ArchitectureRule.of(NO_SECOND_GATE, noSecondGate(),
                        "Remove the role or authority check. Which of a service's roles may perform an operation is"
                                + " decided by that service in ludwig.security.roles; code naming a role takes the"
                                + " decision away from it, and two gates on one endpoint are two places to change it."),
                ArchitectureRule.of(MAPPED_UNDER_THE_VERSIONED_PREFIX, mappedUnderTheVersionedPrefix(),
                        "Map the handler under /admin/v1/. Everything under /admin/ is versioned, as the surface"
                                + " guard and every administrative client expect."),
                ArchitectureRule.of(CONTROLLER_IS_A_THIN_ADAPTER, controllerIsAThinAdapter(context),
                        "Delegate to the module's service instead of reading a repository from the controller."
                                + " The service owns the transaction, the tenant predicate and the audit record of"
                                + " the administrative change."),
                ArchitectureRule.of(ADMIN_OBJECT_IS_A_PATH_VARIABLE, adminObjectIsAPathVariable(),
                        "Put @AdminObject on the @PathVariable that names the object. The surface guard checks the"
                                + " object from the matched URI template before the handler runs; on a request"
                                + " parameter or a body it is never read, and the operation is not scoped."));
    }

    private static ArchRule adminObjectIsAPathVariable() {
        return ArchRuleDefinition.classes()
                .should(new ArchCondition<JavaClass>("mark only path variables with @AdminObject") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (JavaMethod method : item.getMethods()) {
                            method.getParameters().stream()
                                    .filter(parameter -> parameter.isAnnotatedWith(ADMIN_OBJECT))
                                    .filter(parameter -> !parameter.isAnnotatedWith(PATH_VARIABLE))
                                    .forEach(parameter -> events.add(SimpleConditionEvent.violated(item,
                                            String.format("parameter %d of %s carries @AdminObject but is not a"
                                                            + " @PathVariable, in %s", parameter.getIndex(),
                                                    method.getFullName(), item.getSourceCodeLocation()))));
                        }
                    }
                })
                .as("@AdminObject marks a path variable");
    }

    private static ArchRule handlerDeclaresAnOperation() {
        return ArchRuleDefinition.classes()
                .should(new ArchCondition<JavaClass>("declare an operation on every handler under /admin/") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (JavaMethod method : item.getMethods()) {
                            Optional<String> path = handlerPath(item, method);
                            if (path.isPresent() && path.get().startsWith(ADMIN_PREFIX)
                                    && !carriesAny(method, OPERATION_ANNOTATIONS)) {
                                events.add(SimpleConditionEvent.violated(item, String.format(
                                        "%s is mapped to %s and carries neither @RequiresOperation nor"
                                                + " @RequiresAuthentication in %s",
                                        method.getFullName(), path.get(), item.getSourceCodeLocation())));
                            }
                        }
                    }
                })
                .as("A handler under /admin/ declares an administrative operation");
    }

    private static ArchRule noSecondGate() {
        return ArchRuleDefinition.classes()
                .that(declaringOperations())
                .should(new ArchCondition<JavaClass>("carry no second authorization annotation") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (String gate : OTHER_GATES) {
                            if (item.isAnnotatedWith(gate) || item.isMetaAnnotatedWith(gate)) {
                                events.add(violation(item, item.getName(), gate));
                            }
                            item.getMethods().stream()
                                    .filter(method -> carriesAny(method, OPERATION_ANNOTATIONS))
                                    .filter(method -> method.isAnnotatedWith(gate) || method.isMetaAnnotatedWith(gate))
                                    .forEach(method -> events.add(violation(item, method.getFullName(), gate)));
                        }
                    }

                    private ConditionEvent violation(JavaClass item, String where, String gate) {
                        return SimpleConditionEvent.violated(item, String.format(
                                "%s declares an operation, or is the controller of one that does, and is also"
                                        + " annotated with @%s in %s", where, gate, item.getSourceCodeLocation()));
                    }
                })
                .as("A handler declaring an operation has exactly one gate");
    }

    private static ArchRule mappedUnderTheVersionedPrefix() {
        return ArchRuleDefinition.classes()
                .should(new ArchCondition<JavaClass>("map every handler under /admin/ under /admin/v1/") {
                    @Override
                    public void check(JavaClass item, ConditionEvents events) {
                        for (JavaMethod method : item.getMethods()) {
                            Optional<String> path = handlerPath(item, method);
                            if (path.isPresent() && path.get().startsWith(ADMIN_PREFIX)
                                    && !path.get().startsWith(VERSIONED_PREFIX)) {
                                events.add(SimpleConditionEvent.violated(item, String.format(
                                        "%s is an administrative handler mapped to %s, outside /admin/v1/, in %s",
                                        method.getFullName(), path.get(), item.getSourceCodeLocation())));
                            }
                        }
                    }
                })
                .as("A handler under /admin/ is mapped under /admin/v1/");
    }

    private static ArchRule controllerIsAThinAdapter(RuleContext context) {
        return ArchRuleDefinition.classes()
                .that(declaringAdministrativeHandlers())
                .should(ArchitectureConditions.notDependOnClassesThat(
                                ConventionPredicates.repositories(context), "repositories")
                        .as("not reach a repository directly"))
                .as("An administrative controller delegates to a service, never to a repository");
    }

    /**
     * Classes declaring administrative handlers, for the REST path rule, which leaves their path shape to this group.
     *
     * @return the predicate
     */
    static DescribedPredicate<JavaClass> administrativeControllers() {
        return declaringAdministrativeHandlers();
    }

    /** Classes with a handler under {@code /admin/} - administrative by where they answer, whatever they declare. */
    private static DescribedPredicate<JavaClass> declaringAdministrativeHandlers() {
        return DescribedPredicate.describe("classes declaring administrative handlers",
                javaClass -> javaClass.getMethods().stream().anyMatch(method -> handlerPath(javaClass, method)
                        .filter(path -> path.startsWith(ADMIN_PREFIX)).isPresent()));
    }

    /** Classes with a method declaring an operation, on any surface. */
    private static DescribedPredicate<JavaClass> declaringOperations() {
        return DescribedPredicate.describe("classes declaring operations",
                javaClass -> javaClass.getMethods().stream()
                        .anyMatch(method -> carriesAny(method, OPERATION_ANNOTATIONS)));
    }

    private static boolean carriesAny(JavaMethod method, List<String> annotations) {
        return annotations.stream().anyMatch(name -> method.isAnnotatedWith(name) || method.isMetaAnnotatedWith(name));
    }

    /**
     * The path a handler method answers on: the class mapping's first path joined to the method mapping's first.
     *
     * @return empty when the method is not a handler, or a placeholder without a default makes the path unknowable
     */
    private static Optional<String> handlerPath(JavaClass owner, JavaMethod method) {
        Optional<String> methodPath = METHOD_MAPPINGS.stream()
                .filter(method::isAnnotatedWith)
                .findFirst()
                .map(name -> firstPath(method.getAnnotationOfType(name)));
        if (methodPath.isEmpty()) {
            return Optional.empty();
        }
        String classPath = owner.isAnnotatedWith(CLASS_MAPPING)
                ? firstPath(owner.getAnnotationOfType(CLASS_MAPPING))
                : "";
        String joined = classPath + methodPath.get();
        String resolved = resolvePlaceholders(joined);
        return resolved == null ? Optional.empty() : Optional.of(resolved.startsWith("/") ? resolved : "/" + resolved);
    }

    private static String firstPath(JavaAnnotation<?> annotation) {
        for (String attribute : List.of("value", "path")) {
            Optional<Object> value = annotation.get(attribute);
            if (value.isPresent() && value.get() instanceof Object[] paths && paths.length > 0) {
                return String.valueOf(paths[0]);
            }
        }
        return "";
    }

    /**
     * Replaces each {@code ${key:default}} with its default.
     *
     * @return the resolved path, or null when a placeholder has no default
     */
    private static String resolvePlaceholders(String path) {
        StringBuilder resolved = new StringBuilder();
        int index = 0;
        while (index < path.length()) {
            int start = path.indexOf("${", index);
            if (start < 0) {
                resolved.append(path, index, path.length());
                break;
            }
            int end = path.indexOf('}', start);
            int colon = path.indexOf(':', start);
            if (end < 0 || colon < 0 || colon > end) {
                return null;
            }
            resolved.append(path, index, start).append(path, colon + 1, end);
            index = end + 1;
        }
        return resolved.toString();
    }
}
