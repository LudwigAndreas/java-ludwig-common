package ru.ludwigandreas.archrules;

import java.util.List;
import java.util.Optional;

/**
 * Free-form convention values that are neither a package, an annotation nor a type: the name suffix
 * that identifies a mapper, the regular expression an API base path has to match.
 *
 * <p>They live in the same configurable vocabulary as everything else ({@code conventions.settings.*}
 * in a properties file) so that a rule never has to hardcode a string a service might spell
 * differently.
 */
public enum ConventionSetting {

    /**
     * Simple-name suffixes that identify a mapper, alongside the {@link PackageRole#MAPPER} packages.
     * The suffix is only a selector for the mapper convention rule - this library deliberately has
     * no naming rules, which stay with Checkstyle.
     */
    MAPPER_NAME_SUFFIX("mapper-name-suffix", "Mapper"),

    /**
     * Regular expression every {@code @RestController} base path must match, anchored as a whole
     * match. The default encodes the usual {@code /api/v{n}/...} versioning convention; a service or
     * an organisation with a different one configures it here rather than forking the rule.
     */
    REST_BASE_PATH_PATTERN("rest-base-path-pattern", "/api/v\\d+(/.*)?");

    private final String id;
    private final List<String> defaultValues;

    ConventionSetting(String id, String... defaultValues) {
        this.id = id;
        this.defaultValues = List.of(defaultValues);
    }

    public String id() {
        return id;
    }

    public List<String> defaultValues() {
        return defaultValues;
    }

    public static Optional<ConventionSetting> byId(String id) {
        return Enums.byId(values(), ConventionSetting::id, id);
    }
}
