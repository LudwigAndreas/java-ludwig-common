package ru.ludwigandreas.archrules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * The mapping from this library's architectural vocabulary onto one service's actual package layout,
 * annotations and framework types.
 *
 * <p>This is the single place a consuming service adapts the rules to itself. Nothing in
 * {@code ru.ludwigandreas.archrules.rules} hardcodes a package name; every rule asks these
 * conventions for the packages of a {@link PackageRole}, the names of an {@link AnnotationRole}, and
 * so on. A service whose controllers live in {@code ..api.web..} and whose entities live in
 * {@code ..persistence.jpa..} changes two lines here rather than reimplementing the rules.
 *
 * <p>Instances are immutable; {@link #toBuilder()} derives a variant, which is how per-module
 * overrides are expressed (see {@link ModuleRuleCustomization}).
 *
 * <pre>{@code
 * ArchitectureConventions conventions = ArchitectureConventions.defaults().toBuilder()
 *         .packages(PackageRole.CONTROLLER, "..api.web..")
 *         .addPackages(PackageRole.ENTITY, "..persistence.jpa..")
 *         .addAnnotations(AnnotationRole.PERSISTENT_TYPE, "com.acme.ddd.AggregateRoot")
 *         .build();
 * }</pre>
 */
@EqualsAndHashCode
@ToString
public final class ArchitectureConventions {

    private static final ArchitectureConventions DEFAULTS = new Builder(defaultValues()).build();

    private final Map<PackageRole, List<String>> packages;
    private final Map<ExternalLibrary, List<String>> libraryPackages;
    private final Map<AnnotationRole, List<String>> annotations;
    private final Map<TypeRole, List<String>> types;
    private final Map<ConventionSetting, List<String>> settings;

    private ArchitectureConventions(Builder builder) {
        this.packages = copy(PackageRole.class, builder.packages);
        this.libraryPackages = copy(ExternalLibrary.class, builder.libraryPackages);
        this.annotations = copy(AnnotationRole.class, builder.annotations);
        this.types = copy(TypeRole.class, builder.types);
        this.settings = copy(ConventionSetting.class, builder.settings);
    }

    /** The out-of-the-box conventions, matching the package layout this framework's services use. */
    public static ArchitectureConventions defaults() {
        return DEFAULTS;
    }

    /** A builder pre-populated with {@link #defaults()}. */
    public static Builder builder() {
        return DEFAULTS.toBuilder();
    }

    public Builder toBuilder() {
        Values values = new Values();
        values.packages.putAll(packages);
        values.libraryPackages.putAll(libraryPackages);
        values.annotations.putAll(annotations);
        values.types.putAll(types);
        values.settings.putAll(settings);
        return new Builder(values);
    }

    /** ArchUnit package identifiers of the given role, e.g. {@code [..controller.., ..web..]}. */
    public List<String> packages(PackageRole role) {
        return packages.getOrDefault(Objects.requireNonNull(role, "role"), List.of());
    }

    /** ArchUnit package identifiers of the given third-party technology. */
    public List<String> libraryPackages(ExternalLibrary library) {
        return libraryPackages.getOrDefault(Objects.requireNonNull(library, "library"), List.of());
    }

    /** Fully qualified annotation names of the given role. */
    public List<String> annotations(AnnotationRole role) {
        return annotations.getOrDefault(Objects.requireNonNull(role, "role"), List.of());
    }

    /** Fully qualified type names of the given role. */
    public List<String> types(TypeRole role) {
        return types.getOrDefault(Objects.requireNonNull(role, "role"), List.of());
    }

    /** All values configured for a free-form setting; several are allowed where it makes sense. */
    public List<String> settings(ConventionSetting setting) {
        return settings.getOrDefault(Objects.requireNonNull(setting, "setting"), List.of());
    }

    /** The single value of a setting, for the settings that only ever have one. */
    public Optional<String> setting(ConventionSetting setting) {
        List<String> values = settings(setting);
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    private static Values defaultValues() {
        Values values = new Values();
        for (PackageRole role : PackageRole.values()) {
            values.packages.put(role, role.defaultPackageIdentifiers());
        }
        for (ExternalLibrary library : ExternalLibrary.values()) {
            values.libraryPackages.put(library, library.defaultPackageIdentifiers());
        }
        for (AnnotationRole role : AnnotationRole.values()) {
            values.annotations.put(role, role.defaultAnnotationNames());
        }
        for (TypeRole role : TypeRole.values()) {
            values.types.put(role, role.defaultTypeNames());
        }
        for (ConventionSetting setting : ConventionSetting.values()) {
            values.settings.put(setting, setting.defaultValues());
        }
        return values;
    }

    private static <K extends Enum<K>> Map<K, List<String>> copy(Class<K> keyType, Map<K, List<String>> source) {
        Map<K, List<String>> copy = new EnumMap<>(keyType);
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    /** Mutable carrier shared by {@link Builder} and the default-value factory. */
    private static final class Values {
        private final Map<PackageRole, List<String>> packages = new EnumMap<>(PackageRole.class);
        private final Map<ExternalLibrary, List<String>> libraryPackages = new EnumMap<>(ExternalLibrary.class);
        private final Map<AnnotationRole, List<String>> annotations = new EnumMap<>(AnnotationRole.class);
        private final Map<TypeRole, List<String>> types = new EnumMap<>(TypeRole.class);
        private final Map<ConventionSetting, List<String>> settings = new EnumMap<>(ConventionSetting.class);
    }

    /**
     * Fluent builder. {@code xxx(...)} replaces the values of a role outright, {@code addXxx(...)}
     * appends to them - the distinction matters, because replacing {@code CONTROLLER} with
     * {@code ..api..} turns off the default {@code ..controller..} convention, while adding keeps
     * both recognised.
     */
    public static final class Builder {

        private final Map<PackageRole, List<String>> packages;
        private final Map<ExternalLibrary, List<String>> libraryPackages;
        private final Map<AnnotationRole, List<String>> annotations;
        private final Map<TypeRole, List<String>> types;
        private final Map<ConventionSetting, List<String>> settings;

        private Builder(Values values) {
            this.packages = values.packages;
            this.libraryPackages = values.libraryPackages;
            this.annotations = values.annotations;
            this.types = values.types;
            this.settings = values.settings;
        }

        public Builder packages(PackageRole role, String... packageIdentifiers) {
            return packages(role, Arrays.asList(packageIdentifiers));
        }

        public Builder packages(PackageRole role, Collection<String> packageIdentifiers) {
            return replace(packages, role, packageIdentifiers);
        }

        public Builder addPackages(PackageRole role, String... packageIdentifiers) {
            return addPackages(role, Arrays.asList(packageIdentifiers));
        }

        public Builder addPackages(PackageRole role, Collection<String> packageIdentifiers) {
            return append(packages, role, packageIdentifiers);
        }

        public Builder libraryPackages(ExternalLibrary library, String... packageIdentifiers) {
            return libraryPackages(library, Arrays.asList(packageIdentifiers));
        }

        public Builder libraryPackages(ExternalLibrary library, Collection<String> packageIdentifiers) {
            return replace(libraryPackages, library, packageIdentifiers);
        }

        public Builder addLibraryPackages(ExternalLibrary library, String... packageIdentifiers) {
            return addLibraryPackages(library, Arrays.asList(packageIdentifiers));
        }

        public Builder addLibraryPackages(ExternalLibrary library, Collection<String> packageIdentifiers) {
            return append(libraryPackages, library, packageIdentifiers);
        }

        public Builder annotations(AnnotationRole role, String... annotationNames) {
            return annotations(role, Arrays.asList(annotationNames));
        }

        public Builder annotations(AnnotationRole role, Collection<String> annotationNames) {
            return replace(annotations, role, annotationNames);
        }

        public Builder addAnnotations(AnnotationRole role, String... annotationNames) {
            return addAnnotations(role, Arrays.asList(annotationNames));
        }

        public Builder addAnnotations(AnnotationRole role, Collection<String> annotationNames) {
            return append(annotations, role, annotationNames);
        }

        public Builder types(TypeRole role, String... typeNames) {
            return types(role, Arrays.asList(typeNames));
        }

        public Builder types(TypeRole role, Collection<String> typeNames) {
            return replace(types, role, typeNames);
        }

        public Builder addTypes(TypeRole role, String... typeNames) {
            return addTypes(role, Arrays.asList(typeNames));
        }

        public Builder addTypes(TypeRole role, Collection<String> typeNames) {
            return append(types, role, typeNames);
        }

        public Builder settings(ConventionSetting setting, String... values) {
            return settings(setting, Arrays.asList(values));
        }

        public Builder settings(ConventionSetting setting, Collection<String> values) {
            return replace(settings, setting, values);
        }

        public Builder addSettings(ConventionSetting setting, String... values) {
            return addSettings(setting, Arrays.asList(values));
        }

        public Builder addSettings(ConventionSetting setting, Collection<String> values) {
            return append(settings, setting, values);
        }

        public ArchitectureConventions build() {
            return new ArchitectureConventions(this);
        }

        private <K extends Enum<K>> Builder replace(Map<K, List<String>> target, K key, Collection<String> values) {
            Objects.requireNonNull(key, "key");
            target.put(key, List.copyOf(new LinkedHashSet<>(requireNoBlanks(values))));
            return this;
        }

        private <K extends Enum<K>> Builder append(Map<K, List<String>> target, K key, Collection<String> values) {
            Objects.requireNonNull(key, "key");
            Collection<String> merged = new LinkedHashSet<>(target.getOrDefault(key, List.of()));
            merged.addAll(requireNoBlanks(values));
            target.put(key, List.copyOf(merged));
            return this;
        }

        private Collection<String> requireNoBlanks(Collection<String> values) {
            Objects.requireNonNull(values, "values");
            List<String> cleaned = new ArrayList<>(values.size());
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    throw new IllegalArgumentException("Convention values must not be blank, got: " + values);
                }
                cleaned.add(value.trim());
            }
            return cleaned;
        }
    }
}
