package ru.ludwigandreas.archrules.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.TryCatchBlock;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * Conditions the built-in rule sets are written in terms of, published for reuse by a service's own
 * rule sets.
 *
 * <p>They are all phrased positively ("should not depend on X") and emit violations directly, rather
 * than being inverted by {@code noClasses()}. That is deliberate: ArchUnit's {@code no...} form
 * inverts the events of a condition, which silently turns a hand-written condition inside out, and
 * the positive form also produces a violation per offending dependency instead of one per class,
 * which is what makes the failure message actionable.
 */
public final class ArchitectureConditions {

    private ArchitectureConditions() {
    }

    /**
     * Fails for every dependency the class has on a class matching {@code targets}. Dependencies
     * within one top-level class (an inner class referring to its outer class) are ignored - they
     * are not architectural coupling.
     */
    public static ArchCondition<JavaClass> notDependOnClassesThat(DescribedPredicate<? super JavaClass> targets,
                                                                 String description) {
        Objects.requireNonNull(targets, "targets");
        return new ArchCondition<>("not depend on " + description) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass().getBaseComponentType();
                    if (sameTopLevelClass(item, target)) {
                        continue;
                    }
                    if (targets.test(target)) {
                        events.add(SimpleConditionEvent.violated(item, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /**
     * Fails for every field type, method return type, method parameter type or constructor parameter
     * type matching {@code types} - including the type arguments of a generic signature, so a
     * {@code ResponseEntity<PageResponse<ProductEntity>>} is caught just like a bare
     * {@code ProductEntity}.
     */
    public static ArchCondition<JavaClass> notDeclareMembersWithTypesThat(DescribedPredicate<? super JavaClass> types,
                                                                         String description) {
        Objects.requireNonNull(types, "types");
        return new ArchCondition<>("not declare members with " + description) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaField field : item.getFields()) {
                    for (JavaClass offending : matching(involvedTypes(field.getType()), types)) {
                        events.add(SimpleConditionEvent.violated(item, String.format(
                                "Field %s has type %s in %s",
                                field.getFullName(), offending.getName(), field.getSourceCodeLocation())));
                    }
                }
                for (JavaCodeUnit codeUnit : item.getCodeUnits()) {
                    for (JavaClass offending : matching(signatureTypes(codeUnit), types)) {
                        events.add(SimpleConditionEvent.violated(item, String.format(
                                "%s has %s in its signature in %s",
                                codeUnit.getFullName(), offending.getName(), codeUnit.getSourceCodeLocation())));
                    }
                }
            }
        };
    }

    /**
     * The method-level counterpart of {@link #notDeclareMembersWithTypesThat}: fails for every
     * return or parameter type of the method matching {@code types}.
     */
    public static ArchCondition<JavaMethod> notHaveSignatureTypesThat(DescribedPredicate<? super JavaClass> types,
                                                                      String description) {
        Objects.requireNonNull(types, "types");
        return new ArchCondition<>("not have " + description + " in their signature") {
            @Override
            public void check(JavaMethod item, ConditionEvents events) {
                for (JavaClass offending : matching(signatureTypes(item), types)) {
                    events.add(SimpleConditionEvent.violated(item, String.format(
                            "%s has %s in its signature in %s",
                            item.getFullName(), offending.getName(), item.getSourceCodeLocation())));
                }
            }
        };
    }

    /**
     * Fails for every call to one of the given methods, keyed by the fully qualified name of the
     * declaring type. An empty method name set means "any method of that type".
     */
    public static ArchCondition<JavaClass> notCallMethods(Map<String, Set<String>> methodsByOwner,
                                                          String description) {
        Map<String, Set<String>> targets = Map.copyOf(Objects.requireNonNull(methodsByOwner, "methodsByOwner"));
        return new ArchCondition<>("not call " + description) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaCodeUnit codeUnit : item.getCodeUnits()) {
                    for (JavaMethodCall call : codeUnit.getMethodCallsFromSelf()) {
                        Set<String> methodNames = targets.get(call.getTargetOwner().getName());
                        if (methodNames != null && (methodNames.isEmpty() || methodNames.contains(call.getName()))) {
                            events.add(SimpleConditionEvent.violated(item, call.getDescription()));
                        }
                    }
                }
            }
        };
    }

    /**
     * Fails when the class carries any of the given annotations, meta-annotations included. Phrased
     * as a condition rather than as {@code noClasses().should().beAnnotatedWith(..)} so that several
     * annotation names produce one readable rule instead of a chain of {@code orShould}.
     */
    public static ArchCondition<JavaClass> notBeAnnotatedWithAny(Collection<String> annotationNames,
                                                                 String description) {
        List<String> names = List.copyOf(Objects.requireNonNull(annotationNames, "annotationNames"));
        return new ArchCondition<>("not be annotated with " + description) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (String name : names) {
                    if (item.isAnnotatedWith(name) || item.isMetaAnnotatedWith(name)) {
                        events.add(SimpleConditionEvent.violated(item, String.format(
                                "%s is annotated with @%s in %s",
                                item.getName(), name, item.getSourceCodeLocation())));
                    }
                }
            }
        };
    }

    /**
     * Fails for every dependency on a class that lives in another module's internals, i.e. below a
     * package segment named {@code internalSegment} of a module the depending class is not part of.
     *
     * <p>The module is derived from the position of the internal segment itself
     * ({@code com.acme.orders.internal.jpa} belongs to {@code com.acme.orders}), so this works
     * without the modules having to be enumerated anywhere. Only classes inside {@code basePackages}
     * are considered, so a third-party library with an {@code internal} package of its own does not
     * produce noise.
     */
    public static ArchCondition<JavaClass> notDependOnInternalsOfOtherModules(Collection<String> basePackages,
                                                                             String internalSegment) {
        List<String> roots = List.copyOf(Objects.requireNonNull(basePackages, "basePackages"));
        String segment = Objects.requireNonNull(internalSegment, "internalSegment");
        String infix = "." + segment + ".";
        String suffix = "." + segment;
        return new ArchCondition<>("not depend on classes below a '" + segment + "' package of another module") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass().getBaseComponentType();
                    if (sameTopLevelClass(item, target)) {
                        continue;
                    }
                    String targetPackage = target.getPackageName();
                    if (!isInside(roots, targetPackage)) {
                        continue;
                    }
                    int index = indexOfInternalSegment(targetPackage);
                    if (index < 0) {
                        continue;
                    }
                    String owningModule = targetPackage.substring(0, index);
                    if (!isInside(List.of(owningModule), item.getPackageName())) {
                        events.add(SimpleConditionEvent.violated(item, String.format(
                                "%s (internals of module %s)", dependency.getDescription(), owningModule)));
                    }
                }
            }

            private int indexOfInternalSegment(String packageName) {
                int index = packageName.indexOf(infix);
                if (index >= 0) {
                    return index;
                }
                return packageName.endsWith(suffix) ? packageName.length() - suffix.length() : -1;
            }
        };
    }

    /**
     * Fails when a member carries any of the given annotations. The member-level counterpart of
     * {@link #notBeAnnotatedWithAny(Collection, String)}.
     */
    public static <T extends JavaMember> ArchCondition<T> membersNotAnnotatedWithAny(
            Collection<String> annotationNames, String description) {
        List<String> names = List.copyOf(Objects.requireNonNull(annotationNames, "annotationNames"));
        return new ArchCondition<>("not be annotated with " + description) {
            @Override
            public void check(T item, ConditionEvents events) {
                for (String name : names) {
                    if (item.isAnnotatedWith(name) || item.isMetaAnnotatedWith(name)) {
                        events.add(SimpleConditionEvent.violated(item, String.format(
                                "%s is annotated with @%s in %s",
                                item.getFullName(), name, item.getSourceCodeLocation())));
                    }
                }
            }
        };
    }

    /**
     * Fails for every dependency on another module that does not go through that module's public API
     * package. The counterpart of {@link #notDependOnInternalsOfOtherModules}: that one forbids
     * reaching into a package explicitly marked internal, this one allows nothing but the package
     * explicitly marked public.
     *
     * @param modulePackages the module roots, e.g. {@code [com.acme.orders, com.acme.billing]}
     * @param apiSegments    package segments that make up a module's public API, e.g. {@code [api]}
     */
    public static ArchCondition<JavaClass> onlyDependOnOtherModulesThroughTheirApi(Collection<String> modulePackages,
                                                                                   Collection<String> apiSegments) {
        List<String> modules = List.copyOf(Objects.requireNonNull(modulePackages, "modulePackages"));
        List<String> api = List.copyOf(Objects.requireNonNull(apiSegments, "apiSegments"));
        return new ArchCondition<>("only depend on other modules through their " + api + " package") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String originModule = moduleOf(item.getPackageName());
                for (Dependency dependency : item.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass().getBaseComponentType();
                    if (sameTopLevelClass(item, target)) {
                        continue;
                    }
                    String targetPackage = target.getPackageName();
                    String targetModule = moduleOf(targetPackage);
                    if (targetModule == null || targetModule.equals(originModule)) {
                        continue;
                    }
                    if (api.stream().noneMatch(segment -> isInside(
                            List.of(targetModule + "." + segment), targetPackage))) {
                        events.add(SimpleConditionEvent.violated(item, String.format(
                                "%s (module %s is only reachable through its %s package)",
                                dependency.getDescription(), targetModule, api)));
                    }
                }
            }

            private String moduleOf(String packageName) {
                for (String module : modules) {
                    if (isInside(List.of(module), packageName)) {
                        return module;
                    }
                }
                return null;
            }
        };
    }

    /**
     * Fails for every item it is given, with the same message. Used to turn a missing piece of
     * configuration into a targeted failure: the rule is phrased so that it only selects the classes
     * the real check would have applied to, so a service with no Kafka producer - or no entity, or no
     * custom exception - stays green, while a service that has them is told exactly what to set.
     */
    public static <T> ArchCondition<T> alwaysViolate(String description, String message) {
        Objects.requireNonNull(message, "message");
        return new ArchCondition<>(description) {
            @Override
            public void check(T item, ConditionEvents events) {
                events.add(SimpleConditionEvent.violated(item, message + " (" + nameOf(item) + ")"));
            }

            private String nameOf(T item) {
                if (item instanceof JavaClass javaClass) {
                    return javaClass.getName();
                }
                if (item instanceof JavaMember member) {
                    return member.getFullName();
                }
                return String.valueOf(item);
            }
        };
    }

    /**
     * Fails for every public setter the class declares: a public method named {@code setXxx} taking
     * exactly one parameter.
     *
     * <p>Deliberately a check of the compiled API surface rather than of how it was produced -
     * a Lombok-generated setter and a hand-written one are the same bytecode, and that is precisely
     * why this rule works where "detect Lombok usage" cannot.
     */
    public static ArchCondition<JavaClass> notDeclarePublicSetters() {
        return new ArchCondition<>("not declare public setters") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethod method : item.getMethods()) {
                    if (isPublicSetter(method)) {
                        events.add(SimpleConditionEvent.violated(item, String.format(
                                "%s is a public setter in %s",
                                method.getFullName(), method.getSourceCodeLocation())));
                    }
                }
            }

            private boolean isPublicSetter(JavaMethod method) {
                return method.getModifiers().contains(JavaModifier.PUBLIC)
                        && !method.getModifiers().contains(JavaModifier.STATIC)
                        && method.getName().length() > 3
                        && method.getName().startsWith("set")
                        && Character.isUpperCase(method.getName().charAt(3))
                        && method.getRawParameterTypes().size() == 1;
            }
        };
    }

    /**
     * Fails for every {@code catch} of a checked exception in the code unit.
     *
     * <p>{@code java.lang.Throwable} is excluded on purpose: try-with-resources and {@code finally}
     * both compile into catch-all handlers that are indistinguishable from a hand-written
     * {@code catch (Throwable t)} in bytecode, and flagging those would make the rule unusable. Every
     * checked type a developer actually names - {@code IOException}, {@code Exception}, a checked
     * exception of the service's own - is reported.
     */
    public static ArchCondition<JavaCodeUnit> notCatchCheckedExceptions() {
        return new ArchCondition<>("not catch checked exceptions") {
            @Override
            public void check(JavaCodeUnit item, ConditionEvents events) {
                for (TryCatchBlock tryCatchBlock : item.getTryCatchBlocks()) {
                    for (JavaClass caught : tryCatchBlock.getCaughtThrowables()) {
                        if (isCheckedAndNamed(caught)) {
                            events.add(SimpleConditionEvent.violated(item, String.format(
                                    "%s catches checked exception %s in %s",
                                    item.getFullName(), caught.getName(),
                                    tryCatchBlock.getSourceCodeLocation())));
                        }
                    }
                }
            }

            private boolean isCheckedAndNamed(JavaClass caught) {
                return !caught.getName().equals(Throwable.class.getName())
                        && !caught.isAssignableTo(RuntimeException.class.getName())
                        && !caught.isAssignableTo(Error.class.getName());
            }
        };
    }

    /**
     * Fails when a class declares an instance field that is not final - the shared mutable state a
     * singleton bean must not have. Static fields are left alone (constants and loggers), as are
     * synthetic fields the compiler and the coverage agent add.
     */
    public static ArchCondition<JavaClass> notDeclareNonFinalInstanceFields() {
        return new ArchCondition<>("not declare non-final instance fields") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaField field : item.getFields()) {
                    Set<JavaModifier> modifiers = field.getModifiers();
                    if (modifiers.contains(JavaModifier.STATIC)
                            || modifiers.contains(JavaModifier.SYNTHETIC)
                            || modifiers.contains(JavaModifier.FINAL)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(item, String.format(
                            "%s is a non-final instance field in %s",
                            field.getFullName(), field.getSourceCodeLocation())));
                }
            }
        };
    }

    /**
     * Fails when a class does not declare a base path through one of the given annotations, or when
     * the declared path does not match {@code pathPattern}.
     *
     * <p>Implemented against the annotation's member values ({@code value} or {@code path}) rather
     * than as a dependency rule, because the interesting part is the string inside the annotation,
     * which a dependency check cannot see.
     */
    public static ArchCondition<JavaClass> haveBasePathMatching(Collection<String> mappingAnnotations,
                                                                Pattern pathPattern) {
        List<String> annotations = List.copyOf(Objects.requireNonNull(mappingAnnotations, "mappingAnnotations"));
        Objects.requireNonNull(pathPattern, "pathPattern");
        return new ArchCondition<>("declare a base path matching '" + pathPattern.pattern() + "'") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                List<String> declaredPaths = declaredPaths(item);
                if (declaredPaths.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(item, String.format(
                            "%s declares no base path (expected @%s with a path matching '%s') in %s",
                            item.getName(), simpleName(annotations.get(0)), pathPattern.pattern(),
                            item.getSourceCodeLocation())));
                    return;
                }
                for (String path : declaredPaths) {
                    if (!pathPattern.matcher(path).matches()) {
                        events.add(SimpleConditionEvent.violated(item, String.format(
                                "%s declares base path '%s', which does not match '%s' in %s",
                                item.getName(), path, pathPattern.pattern(), item.getSourceCodeLocation())));
                    }
                }
            }

            private List<String> declaredPaths(JavaClass item) {
                List<String> paths = new ArrayList<>();
                for (String annotationName : annotations) {
                    item.tryGetAnnotationOfType(annotationName)
                            .ifPresent(annotation -> {
                                paths.addAll(stringValues(annotation.get("value")));
                                paths.addAll(stringValues(annotation.get("path")));
                            });
                }
                return paths;
            }

            private List<String> stringValues(Optional<Object> member) {
                if (member.isEmpty()) {
                    return List.of();
                }
                Object value = member.get();
                List<String> values = new ArrayList<>();
                if (value instanceof Object[] array) {
                    for (Object element : array) {
                        if (element != null) {
                            values.add(element.toString());
                        }
                    }
                } else {
                    values.add(value.toString());
                }
                values.removeIf(String::isEmpty);
                return values;
            }

            private String simpleName(String typeName) {
                int lastDot = typeName.lastIndexOf('.');
                return lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
            }
        };
    }

    /** Fails when the class is not assignable to any of the given types. */
    public static ArchCondition<JavaClass> beAssignableToAny(Collection<String> typeNames, String description) {
        List<String> names = List.copyOf(Objects.requireNonNull(typeNames, "typeNames"));
        return new ArchCondition<>("be assignable to " + description) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (names.stream().noneMatch(item::isAssignableTo)) {
                    events.add(SimpleConditionEvent.violated(item, String.format(
                            "%s does not extend %s in %s",
                            item.getName(), names.size() == 1 ? names.get(0) : "any of " + names,
                            item.getSourceCodeLocation())));
                }
            }
        };
    }

    /** Fails when the class carries none of the given annotations. */
    public static ArchCondition<JavaClass> beAnnotatedWithAny(Collection<String> annotationNames,
                                                              String description) {
        List<String> names = List.copyOf(Objects.requireNonNull(annotationNames, "annotationNames"));
        return new ArchCondition<>("be annotated with " + description) {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean annotated = names.stream()
                        .anyMatch(name -> item.isAnnotatedWith(name) || item.isMetaAnnotatedWith(name));
                if (!annotated) {
                    events.add(SimpleConditionEvent.violated(item, String.format(
                            "%s is not annotated with %s in %s",
                            item.getName(), names.size() == 1 ? "@" + names.get(0) : "any of " + names,
                            item.getSourceCodeLocation())));
                }
            }
        };
    }

    /** Fails for every field whose declared type is one of the given types. */
    public static ArchCondition<JavaField> notHaveRawTypeAnyOf(Collection<String> typeNames, String description) {
        List<String> names = List.copyOf(Objects.requireNonNull(typeNames, "typeNames"));
        return new ArchCondition<>("not be declared as " + description) {
            @Override
            public void check(JavaField item, ConditionEvents events) {
                if (names.contains(item.getRawType().getName())) {
                    events.add(SimpleConditionEvent.violated(item, String.format(
                            "Field %s is declared as %s in %s",
                            item.getFullName(), item.getRawType().getName(), item.getSourceCodeLocation())));
                }
            }
        };
    }

    /** Fails for every parameter of the code unit whose declared type is one of the given types. */
    public static ArchCondition<JavaCodeUnit> notHaveParameterTypeAnyOf(Collection<String> typeNames,
                                                                        String description) {
        List<String> names = List.copyOf(Objects.requireNonNull(typeNames, "typeNames"));
        return new ArchCondition<>("not take " + description + " as a parameter") {
            @Override
            public void check(JavaCodeUnit item, ConditionEvents events) {
                for (JavaClass parameterType : item.getRawParameterTypes()) {
                    if (names.contains(parameterType.getName())) {
                        events.add(SimpleConditionEvent.violated(item, String.format(
                                "%s takes %s as a parameter in %s",
                                item.getFullName(), parameterType.getName(), item.getSourceCodeLocation())));
                    }
                }
            }
        };
    }

    /** Whether {@code packageName} is one of the roots or nested below one of them. */
    public static boolean isInside(Collection<String> roots, String packageName) {
        for (String root : roots) {
            if (packageName.equals(root) || packageName.startsWith(root + ".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameTopLevelClass(JavaClass first, JavaClass second) {
        return topLevelClass(first).equals(topLevelClass(second));
    }

    private static JavaClass topLevelClass(JavaClass javaClass) {
        JavaClass current = javaClass.getBaseComponentType();
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }

    private static Set<JavaClass> signatureTypes(JavaCodeUnit codeUnit) {
        Set<JavaClass> involved = new LinkedHashSet<>(involvedTypes(codeUnit.getReturnType()));
        for (JavaType parameterType : codeUnit.getParameterTypes()) {
            involved.addAll(involvedTypes(parameterType));
        }
        return involved;
    }

    /** The raw types a (possibly generic) type is made of, e.g. {@code List<ProductEntity>}. */
    private static Set<JavaClass> involvedTypes(JavaType type) {
        Set<JavaClass> raw = new LinkedHashSet<>();
        for (JavaClass involved : type.getAllInvolvedRawTypes()) {
            raw.add(involved.getBaseComponentType());
        }
        return raw;
    }

    private static List<JavaClass> matching(Collection<JavaClass> candidates,
                                            DescribedPredicate<? super JavaClass> predicate) {
        return candidates.stream().filter(predicate).toList();
    }
}
