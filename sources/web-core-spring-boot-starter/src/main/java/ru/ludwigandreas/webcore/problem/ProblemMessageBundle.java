package ru.ludwigandreas.webcore.problem;

import org.springframework.core.Ordered;

/**
 * A module's contribution of error text to the shared problem pipeline.
 *
 * <p>Registering one of these as a bean is how a starter makes its own errors speak the caller's
 * language:
 *
 * <pre>{@code
 * @Bean
 * ProblemMessageBundle odataFilterProblemMessages() {
 *     return ProblemMessageBundle.of("i18n/ludwig-odata-filter-messages");
 * }
 * }</pre>
 *
 * <p>The alternative - each module resolving its own text against its own {@code MessageSource} -
 * is what produced the problem this class exists to remove. A module cannot take over the
 * application's {@code MessageSource}, and it must not require every consumer to copy its keys in
 * before its errors are readable, so each one grew a private fallback bundle and a private lookup.
 * The result was as many error-rendering paths as there were modules, only one of which the
 * application could actually override.
 *
 * <p>Here the application's own bundle is always consulted first (see {@link ProblemMessages}), so
 * overriding any module's wording means defining the same key locally - no fork, no configuration.
 *
 * @param basename a {@code ResourceBundle} basename with no locale suffix and no {@code .properties}
 *                 extension, resolved from the classpath: {@code i18n/ludwig-web-messages}
 * @param order    relative precedence among contributed bundles; lower wins
 */
public record ProblemMessageBundle(String basename, int order) implements Ordered {

    public ProblemMessageBundle {
        if (basename == null || basename.isBlank()) {
            throw new IllegalArgumentException("A ProblemMessageBundle needs a basename");
        }
        if (basename.endsWith(".properties")) {
            throw new IllegalArgumentException(
                    "A ProblemMessageBundle basename must not include the .properties extension: "
                            + basename);
        }
    }

    public static ProblemMessageBundle of(String basename) {
        return new ProblemMessageBundle(basename, Ordered.LOWEST_PRECEDENCE);
    }

    public static ProblemMessageBundle of(String basename, int order) {
        return new ProblemMessageBundle(basename, order);
    }

    @Override
    public int getOrder() {
        return order;
    }
}
