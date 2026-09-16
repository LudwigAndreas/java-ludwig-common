package ru.ludwigandreas.archrules.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeSet;

import ru.ludwigandreas.archrules.AnnotationRole;
import ru.ludwigandreas.archrules.ArchitectureConventions;
import ru.ludwigandreas.archrules.ArchitectureRulesConfiguration;
import ru.ludwigandreas.archrules.ExternalLibrary;
import ru.ludwigandreas.archrules.ModuleRuleCustomization;
import ru.ludwigandreas.archrules.ConventionSetting;
import ru.ludwigandreas.archrules.PackageRole;
import ru.ludwigandreas.archrules.RuleSeverity;
import ru.ludwigandreas.archrules.SeverityPolicy;
import ru.ludwigandreas.archrules.report.ColorMode;
import ru.ludwigandreas.archrules.TypeRole;

import com.tngtech.archunit.core.importer.ImportOption;

/**
 * Reads an {@link ArchitectureRulesConfiguration} from a properties file, so that a service can
 * adjust the rules without touching test code - the form a platform team usually wants, because a
 * properties file can be templated, reviewed and diffed across services.
 *
 * <p>The file is {@code architecture-rules.properties} on the test classpath by default, and every
 * key is optional:
 *
 * <pre>{@code
 * architecture.rules.base-packages = com.acme.orders
 * architecture.rules.modules = com.acme.orders.catalog, com.acme.orders.shipping
 *
 * # toggles: a group id, a rule id, or *
 * architecture.rules.rules.kafka = false
 * architecture.rules.rules.domain-isolation = true
 * architecture.rules.rules.web.controllers-do-not-call-controllers = false
 *
 * # conventions: replace a role's values, or add to them with the .add suffix
 * architecture.rules.conventions.packages.controller = ..api.web..
 * architecture.rules.conventions.packages.entity.add = ..persistence.jpa..
 * architecture.rules.conventions.libraries.aws-sdk.add = com.acme.storagesdk..
 * architecture.rules.conventions.annotations.persistent-type.add = com.acme.ddd.AggregateRoot
 * architecture.rules.conventions.types.kafka-client.add = com.acme.kafka.Publisher
 *
 * # per-module deviations, the label after 'module.' is free-form
 * architecture.rules.module.legacy.packages = com.acme.orders.legacy
 * architecture.rules.module.legacy.rules.layering = false
 * architecture.rules.module.legacy.conventions.packages.entity.add = ..jpa..
 *
 # severity: a violated rule at warning severity is reported everywhere but does not fail the build
 * architecture.rules.severity.modules = warning
 * architecture.rules.severity.web.controllers-do-not-call-controllers = warning
 *
 * # reports
 * architecture.rules.service-name = orders-service
 * architecture.rules.report.console = true
 * architecture.rules.report.color = auto
 * architecture.rules.report.json = true
 * architecture.rules.report.json-file = target/architecture-report.json
 * architecture.rules.report.max-violations-per-rule = 5
 *
 * architecture.rules.allow-empty-should = true
 * architecture.rules.freeze = false
 * architecture.rules.built-in-rule-sets = true
 * architecture.rules.service-loader-rule-sets = true
 * architecture.rules.include-tests = false
 * }</pre>
 *
 * <p>An unknown key under {@code architecture.rules.} fails the build rather than being ignored: a
 * typo in a rule id would otherwise switch nothing off and be discovered only when the rule fires.
 */
public final class ArchitectureRulesProperties {

    /** Resource name loaded from the test classpath when nothing else is specified. */
    public static final String DEFAULT_RESOURCE = "architecture-rules.properties";

    /** Prefix every key of this library carries. */
    public static final String PREFIX = "architecture.rules.";

    private static final String KEY_BASE_PACKAGES = "base-packages";
    private static final String KEY_MODULES = "modules";
    private static final String KEY_RULES = "rules.";
    private static final String KEY_SEVERITY = "severity.";
    private static final String KEY_REPORT = "report.";
    private static final String KEY_SERVICE_NAME = "service-name";
    private static final String KEY_CONVENTIONS = "conventions.";
    private static final String KEY_MODULE = "module.";
    private static final String KEY_ALLOW_EMPTY_SHOULD = "allow-empty-should";
    private static final String KEY_ALLOW_EMPTY_ANALYSIS = "allow-empty-analysis";
    private static final String KEY_FREEZE = "freeze";
    private static final String KEY_BUILT_IN_RULE_SETS = "built-in-rule-sets";
    private static final String KEY_SERVICE_LOADER_RULE_SETS = "service-loader-rule-sets";
    private static final String KEY_INCLUDE_TESTS = "include-tests";
    private static final String ADD_SUFFIX = ".add";

    private ArchitectureRulesProperties() {
    }

    /** Loads the named classpath resource, or empty properties when it does not exist. */
    public static Properties fromClasspath(String resourceName) {
        Objects.requireNonNull(resourceName, "resourceName");
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader() != null
                ? Thread.currentThread().getContextClassLoader()
                : ArchitectureRulesProperties.class.getClassLoader();
        Properties properties = new Properties();
        try (InputStream stream = classLoader.getResourceAsStream(resourceName)) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to read architecture rules properties from " + resourceName,
                    exception);
        }
        return properties;
    }

    /** Applies every {@code architecture.rules.*} key to the given builder. */
    public static ArchitectureRulesConfiguration.Builder applyTo(Properties properties,
                                                                 ArchitectureRulesConfiguration.Builder builder) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(builder, "builder");

        List<ConventionEdit> conventionEdits = new ArrayList<>();
        Map<String, ModuleProperties> moduleProperties = new LinkedHashMap<>();

        for (String name : new TreeSet<>(properties.stringPropertyNames())) {
            if (!name.startsWith(PREFIX)) {
                continue;
            }
            String key = name.substring(PREFIX.length());
            String value = Optional.ofNullable(properties.getProperty(name)).orElse("").trim();
            if (key.startsWith(KEY_MODULE)) {
                collectModuleProperty(moduleProperties, name, key.substring(KEY_MODULE.length()), value);
            } else if (key.startsWith(KEY_CONVENTIONS)) {
                conventionEdits.add(parseConventionEdit(name, key.substring(KEY_CONVENTIONS.length()), value));
            } else {
                applyTopLevel(builder, name, key, value);
            }
        }

        if (!conventionEdits.isEmpty()) {
            builder.conventions(conventions -> applyEdits(conventions, conventionEdits));
        }
        moduleProperties.values().forEach(module -> builder.customizeModules(module.toCustomization()));
        return builder;
    }

    private static void applyTopLevel(ArchitectureRulesConfiguration.Builder builder,
                                      String fullKey, String key, String value) {
        switch (key) {
            case KEY_BASE_PACKAGES -> builder.basePackages(split(value));
            case KEY_SERVICE_NAME -> builder.serviceName(value);
            case KEY_MODULES -> builder.modules(split(value));
            case KEY_ALLOW_EMPTY_SHOULD -> builder.allowEmptyShould(parseBoolean(fullKey, value));
            case KEY_ALLOW_EMPTY_ANALYSIS -> builder.allowEmptyAnalysis(parseBoolean(fullKey, value));
            case KEY_FREEZE -> builder.freeze(parseBoolean(fullKey, value));
            case KEY_BUILT_IN_RULE_SETS -> builder.includeBuiltInRuleSets(parseBoolean(fullKey, value));
            case KEY_SERVICE_LOADER_RULE_SETS -> builder.includeServiceLoaderRuleSets(parseBoolean(fullKey, value));
            case KEY_INCLUDE_TESTS -> {
                if (parseBoolean(fullKey, value)) {
                    builder.importOptions(ImportOption.Predefined.DO_NOT_INCLUDE_JARS,
                            ImportOption.Predefined.DO_NOT_INCLUDE_ARCHIVES,
                            ImportOption.Predefined.DO_NOT_INCLUDE_PACKAGE_INFOS);
                }
            }
            default -> {
                if (key.startsWith(KEY_RULES)) {
                    applyToggle(builder, fullKey, key.substring(KEY_RULES.length()), value);
                } else if (key.startsWith(KEY_SEVERITY)) {
                    applySeverity(builder, fullKey, key.substring(KEY_SEVERITY.length()), value);
                } else if (key.startsWith(KEY_REPORT)) {
                    applyReport(builder, fullKey, key.substring(KEY_REPORT.length()), value);
                } else {
                    throw unknownKey(fullKey);
                }
            }
        }
    }

    private static void applyToggle(ArchitectureRulesConfiguration.Builder builder,
                                    String fullKey, String selector, String value) {
        if (parseBoolean(fullKey, value)) {
            builder.enable(selector);
        } else {
            builder.disable(selector);
        }
    }

    private static void applySeverity(ArchitectureRulesConfiguration.Builder builder,
                                      String fullKey, String selector, String value) {
        RuleSeverity severity = RuleSeverity.byId(value).orElseThrow(() -> new IllegalArgumentException(
                "Property '" + fullKey + "' must be one of error, warning, but was '" + value + "'"));
        builder.severities(SeverityPolicy.none().with(severity, selector));
    }

    private static void applyReport(ArchitectureRulesConfiguration.Builder builder,
                                    String fullKey, String key, String value) {
        switch (key) {
            case "console" -> builder.reporting(report -> report.console(parseBoolean(fullKey, value)));
            case "json" -> builder.reporting(report -> report.json(parseBoolean(fullKey, value)));
            case "json-file" -> builder.reporting(report -> report.jsonFile(Path.of(value)));
            case "color" -> builder.reporting(report -> report.color(ColorMode.byId(value)
                    .orElseThrow(() -> new IllegalArgumentException("Property '" + fullKey
                            + "' must be one of auto, always, never, but was '" + value + "'"))));
            case "max-violations-per-rule" -> builder.reporting(report ->
                    report.maxViolationsPerRule(parseInt(fullKey, value)));
            default -> throw unknownKey(fullKey);
        }
    }

    private static int parseInt(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException("Property '" + key + "' must be a number, but was '"
                    + value + "'", notANumber);
        }
    }

    private static void collectModuleProperty(Map<String, ModuleProperties> modules,
                                              String fullKey, String remainder, String value) {
        int separator = remainder.indexOf('.');
        if (separator < 0) {
            throw unknownKey(fullKey);
        }
        String moduleLabel = remainder.substring(0, separator);
        String key = remainder.substring(separator + 1);
        ModuleProperties module = modules.computeIfAbsent(moduleLabel, label -> new ModuleProperties(label));
        if ("packages".equals(key)) {
            module.packages.addAll(split(value));
        } else if (key.startsWith(KEY_RULES)) {
            module.toggles.put(key.substring(KEY_RULES.length()), parseBoolean(fullKey, value));
        } else if (key.startsWith(KEY_CONVENTIONS)) {
            module.conventionEdits.add(parseConventionEdit(fullKey, key.substring(KEY_CONVENTIONS.length()), value));
        } else {
            throw unknownKey(fullKey);
        }
    }

    private static ConventionEdit parseConventionEdit(String fullKey, String key, String value) {
        boolean add = key.endsWith(ADD_SUFFIX);
        String withoutSuffix = add ? key.substring(0, key.length() - ADD_SUFFIX.length()) : key;
        int separator = withoutSuffix.indexOf('.');
        if (separator < 0) {
            throw unknownKey(fullKey);
        }
        String kind = withoutSuffix.substring(0, separator);
        String roleId = withoutSuffix.substring(separator + 1);
        List<String> values = split(value);
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Property '" + fullKey + "' must list at least one value");
        }
        return new ConventionEdit(fullKey, kind, roleId, add, values);
    }

    private static ArchitectureConventions.Builder applyEdits(ArchitectureConventions.Builder conventions,
                                                              List<ConventionEdit> edits) {
        for (ConventionEdit edit : edits) {
            edit.applyTo(conventions);
        }
        return conventions;
    }

    private static List<String> split(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private static boolean parseBoolean(String key, String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("Property '" + key + "' must be true or false, but was '" + value + "'");
    }

    private static IllegalArgumentException unknownKey(String key) {
        return new IllegalArgumentException("Unknown architecture rules property '" + key + "'. Supported keys are "
                + PREFIX + "{base-packages, service-name, modules, rules.<selector>, severity.<selector>, "
                + "conventions.<packages|libraries|annotations|types|settings>.<role>[.add], "
                + "report.{console, color, json, json-file, max-violations-per-rule}, "
                + "module.<label>.{packages, rules.<selector>, conventions...}, "
                + "allow-empty-should, allow-empty-analysis, freeze, built-in-rule-sets, "
                + "service-loader-rule-sets, include-tests}");
    }

    /** One {@code conventions.<kind>.<role>[.add]} entry. */
    private record ConventionEdit(String key, String kind, String roleId, boolean add, List<String> values) {

        void applyTo(ArchitectureConventions.Builder conventions) {
            switch (kind) {
                case "packages" -> {
                    PackageRole role = PackageRole.byId(roleId).orElseThrow(this::unknownRole);
                    if (add) {
                        conventions.addPackages(role, values);
                    } else {
                        conventions.packages(role, values);
                    }
                }
                case "libraries" -> {
                    ExternalLibrary library = ExternalLibrary.byId(roleId).orElseThrow(this::unknownRole);
                    if (add) {
                        conventions.addLibraryPackages(library, values);
                    } else {
                        conventions.libraryPackages(library, values);
                    }
                }
                case "annotations" -> {
                    AnnotationRole role = AnnotationRole.byId(roleId).orElseThrow(this::unknownRole);
                    if (add) {
                        conventions.addAnnotations(role, values);
                    } else {
                        conventions.annotations(role, values);
                    }
                }
                case "settings" -> {
                    ConventionSetting setting = ConventionSetting.byId(roleId).orElseThrow(this::unknownRole);
                    if (add) {
                        conventions.addSettings(setting, values);
                    } else {
                        conventions.settings(setting, values);
                    }
                }
                case "types" -> {
                    TypeRole role = TypeRole.byId(roleId).orElseThrow(this::unknownRole);
                    if (add) {
                        conventions.addTypes(role, values);
                    } else {
                        conventions.types(role, values);
                    }
                }
                default -> throw new IllegalArgumentException("Property '" + key + "' uses unknown convention kind '"
                        + kind + "'; expected one of packages, libraries, annotations, types, settings");
            }
        }

        private IllegalArgumentException unknownRole() {
            return new IllegalArgumentException("Property '" + key + "' names unknown " + kind + " role '" + roleId
                    + "'");
        }
    }

    /** The {@code module.<label>.*} keys collected for one label. */
    private static final class ModuleProperties {

        private final String label;
        private final List<String> packages = new ArrayList<>();
        private final Map<String, Boolean> toggles = new LinkedHashMap<>();
        private final List<ConventionEdit> conventionEdits = new ArrayList<>();

        private ModuleProperties(String label) {
            this.label = label;
        }

        private ModuleRuleCustomization toCustomization() {
            if (packages.isEmpty()) {
                throw new IllegalArgumentException("Module customization '" + label + "' has no packages; add "
                        + PREFIX + KEY_MODULE + label + ".packages");
            }
            ModuleRuleCustomization.Builder builder = ModuleRuleCustomization.forModules(packages);
            toggles.forEach((selector, enabled) -> {
                if (enabled) {
                    builder.enable(selector);
                } else {
                    builder.disable(selector);
                }
            });
            if (!conventionEdits.isEmpty()) {
                builder.conventions(conventions -> applyEdits(conventions, conventionEdits));
            }
            return builder.build();
        }
    }
}
