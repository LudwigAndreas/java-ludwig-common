package ru.ludwigandreas.security.operation;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import ru.ludwigandreas.security.exception.SecurityConfigurationException;

/**
 * Fails startup when the deployment still configures a key the operation model replaced. Binding ignores unknown
 * keys, so without this a service upgraded with its old grants would start with every operation refused - or, worse,
 * read as configured by anyone reviewing the file.
 */
public class RemovedOperationKeys implements InitializingBean {

    private static final Map<String, String> REPLACED = Map.of(
            "ludwig.admin.grants", "ludwig.security.roles.<ROLE>.operations",
            "ludwig.security.data.policies", "ludwig.security.roles.<ROLE>.operations",
            "ludwig.security.data.default-access", "nothing: an operation no role lists is held by nobody",
            "ludwig.security.data.strict-policy-tokens", "nothing: every dimension is checked against its resource",
            "ludwig.admin.allow-personal-access-tokens", "ludwig.security.operations.allow-personal-access-tokens");

    private final ConfigurableEnvironment environment;

    /**
     * Creates the check.
     *
     * @param environment the application's environment
     */
    public RemovedOperationKeys(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> found = environment.getPropertySources().stream()
                .filter(EnumerablePropertySource.class::isInstance)
                .flatMap(source -> List.of(((EnumerablePropertySource<?>) source).getPropertyNames()).stream())
                .flatMap(name -> REPLACED.keySet().stream()
                        .filter(removed -> name.equals(removed) || name.startsWith(removed + ".")
                                || name.startsWith(removed + "["))
                        .map(removed -> name + " (use " + REPLACED.get(removed) + ")"))
                .distinct()
                .sorted()
                .toList();
        if (!found.isEmpty()) {
            throw new SecurityConfigurationException("Configuration keys the operation model replaced are still set:"
                    + "\n  - "
                    + String.join("\n  - ", found));
        }
    }
}
