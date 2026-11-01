package ru.ludwigandreas.identity.projection;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import ru.ludwigandreas.identity.config.IdentityProjectionProperties;
import ru.ludwigandreas.identity.config.IdentityProjectionProperties.UnmappedRoles;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * The declared identity sources and their role vocabularies, compiled once from {@code ludwig.identity.sources}.
 */
public final class IdentitySources {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*");
    private static final String ROLE_PREFIX = "ROLE_";

    private final Map<String, RoleMapping> sources;

    /**
     * Compiles the declared sources.
     *
     * @param declared {@code ludwig.identity.sources}
     * @throws SecurityConfigurationException naming a source whose name is outside the grammar
     */
    public IdentitySources(Map<String, IdentityProjectionProperties.Source> declared) {
        Map<String, RoleMapping> compiled = new LinkedHashMap<>();
        declared.forEach((name, source) -> {
            if (!NAME.matcher(name).matches()) {
                throw new SecurityConfigurationException("ludwig.identity.sources." + name + ": a source name is"
                        + " lower-case letters, digits and hyphens, starting with a letter");
            }
            compiled.put(name, RoleMapping.of(source));
        });
        this.sources = Map.copyOf(compiled);
    }

    /**
     * Whether a source is declared.
     *
     * @param source a source name
     * @return true when it is declared
     */
    public boolean isDeclared(String source) {
        return sources.containsKey(source);
    }

    /**
     * The declared source names.
     *
     * @return the names
     */
    public Set<String> names() {
        return sources.keySet();
    }

    /**
     * A source's roles in the platform's vocabulary.
     *
     * @param source a declared source
     * @param roles  the roles as the source names them
     * @return the platform roles, without the {@code ROLE_} prefix
     * @throws IllegalArgumentException when the source is not declared
     */
    public Set<String> mapRoles(String source, Set<String> roles) {
        return require(source).map(roles);
    }

    /**
     * Fails unless a source is declared.
     *
     * @param source a source name
     * @return the source's role mapping
     * @throws IllegalArgumentException naming the source and the declared ones
     */
    RoleMapping require(String source) {
        RoleMapping mapping = sources.get(source);
        if (mapping == null) {
            throw new IllegalArgumentException("Identity source '" + source + "' is not declared under"
                    + " ludwig.identity.sources (declared: " + sources.keySet() + ")");
        }
        return mapping;
    }

    static String bare(String role) {
        String trimmed = role.trim();
        return trimmed.startsWith(ROLE_PREFIX) ? trimmed.substring(ROLE_PREFIX.length()) : trimmed;
    }

    /** One source's mapping from its role names to the platform's. */
    record RoleMapping(Map<String, Set<String>> targets, boolean keepUnmapped) {

        static RoleMapping of(IdentityProjectionProperties.Source source) {
            Map<String, Set<String>> targets = new LinkedHashMap<>();
            source.getRoleMapping().forEach((from, to) -> {
                Set<String> bareTargets = new LinkedHashSet<>();
                to.forEach(target -> bareTargets.add(bare(target)));
                targets.put(bare(from), Set.copyOf(bareTargets));
            });
            return new RoleMapping(Map.copyOf(targets), source.getUnmappedRoles() == UnmappedRoles.KEEP);
        }

        Set<String> map(Set<String> roles) {
            Set<String> mapped = new LinkedHashSet<>();
            for (String role : roles) {
                if (role == null || role.isBlank()) {
                    continue;
                }
                String name = bare(role);
                Set<String> to = targets.get(name);
                if (to != null) {
                    mapped.addAll(to);
                } else if (keepUnmapped) {
                    mapped.add(name);
                }
            }
            return mapped;
        }
    }
}
