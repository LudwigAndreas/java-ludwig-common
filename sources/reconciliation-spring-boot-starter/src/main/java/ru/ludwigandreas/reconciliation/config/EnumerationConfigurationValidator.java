package ru.ludwigandreas.reconciliation.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.reconciliation.api.EnumerationTask;
import ru.ludwigandreas.reconciliation.api.ReconciliationEnumeration;
import ru.ludwigandreas.reconciliation.exception.ReconciliationConfigurationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Refuses to start on an enumeration configuration that is individually valid and jointly wrong.
 *
 * <p>The sibling of {@link ReconciliationConfigurationValidator} for the second task kind, and written
 * to the same two rules: every problem is reported in one pass, and every message says what breaks
 * rather than what is wrong, because the person reading it is deploying at the time.
 *
 * <p>A separate class rather than more checks in the existing one so that the existing validator's
 * public constructor - which a service replacing it compiles against - does not change.
 */
public class EnumerationConfigurationValidator {

    private static final Logger log = LoggerFactory.getLogger(EnumerationConfigurationValidator.class);

    private final ReconciliationProperties properties;
    private final Map<String, EnumerationSettings> resolved;
    private final List<EnumerationTask<?, ?, ?, ?>> taskBeans;
    private final Predicate<String> restClientExists;

    /**
     * Creates the validator.
     *
     * @param properties       the bound configuration
     * @param resolved         the resolved enumeration settings
     * @param taskBeans        every enumeration bean in the context
     * @param restClientExists whether a named REST client is configured
     */
    public EnumerationConfigurationValidator(ReconciliationProperties properties,
                                             Map<String, EnumerationSettings> resolved,
                                             List<EnumerationTask<?, ?, ?, ?>> taskBeans,
                                             Predicate<String> restClientExists) {
        this.properties = properties;
        this.resolved = resolved;
        this.taskBeans = taskBeans;
        this.restClientExists = restClientExists;
    }

    /**
     * Validates, and throws with every problem found.
     *
     * @throws ReconciliationConfigurationException when the configuration is unsafe
     */
    @PostConstruct
    public void validate() {
        List<String> problems = new ArrayList<>();
        Map<String, EnumerationTask<?, ?, ?, ?>> beansByName = indexBeans(problems);
        checkBeansAndConfigurationAgree(beansByName, problems);
        checkNamesAreNotShared(problems);
        resolved.forEach((name, settings) -> {
            checkReferences(settings, problems);
            checkIncremental(settings, beansByName.get(name), problems);
            checkSweep(settings, problems);
        });
        if (!problems.isEmpty()) {
            throw new ReconciliationConfigurationException(problems);
        }
        if (!resolved.isEmpty()) {
            log.info("Reconciliation enumeration configuration validated: {} enumeration(s)", resolved.size());
        }
    }

    private Map<String, EnumerationTask<?, ?, ?, ?>> indexBeans(List<String> problems) {
        Map<String, EnumerationTask<?, ?, ?, ?>> byName = new LinkedHashMap<>();
        for (EnumerationTask<?, ?, ?, ?> bean : taskBeans) {
            ReconciliationEnumeration annotation = annotationOf(bean);
            String annotated = annotation == null ? null : annotation.value();
            if (annotated != null && !annotated.equals(bean.name())) {
                problems.add(String.format(
                        "Enumeration bean %s is annotated @ReconciliationEnumeration(\"%s\") but its "
                                + "name() returns \"%s\". It would be configured under one name and "
                                + "looked up under the other, so it would start and never run. Make "
                                + "them the same.",
                        bean.getClass().getName(), annotated, bean.name()));
            }
            EnumerationTask<?, ?, ?, ?> previous = byName.put(bean.name(), bean);
            if (previous != null) {
                problems.add(String.format(
                        "Two enumeration beans both claim the name \"%s\" (%s and %s). Only one of "
                                + "them would ever be scheduled, and which one is not defined.",
                        bean.name(), previous.getClass().getName(), bean.getClass().getName()));
            }
        }
        return byName;
    }

    private void checkBeansAndConfigurationAgree(Map<String, EnumerationTask<?, ?, ?, ?>> beansByName,
                                                 List<String> problems) {
        Set<String> configured = new TreeSet<>(resolved.keySet());
        Set<String> implemented = new TreeSet<>(beansByName.keySet());
        for (String name : implemented) {
            if (!configured.contains(name)) {
                problems.add(String.format(
                        "Enumeration bean %s implements enumeration \"%s\", which has no block under "
                                + "ludwig.reconciliation.enumerations. It would never be scheduled, "
                                + "and nothing would say so. Configured enumerations: %s",
                        beansByName.get(name).getClass().getName(), name, orNone(configured)));
            }
        }
        for (String name : configured) {
            if (!implemented.contains(name)) {
                problems.add(String.format(
                        "Enumeration \"%s\" is configured but no @ReconciliationEnumeration bean "
                                + "implements it, so every setting under it is inert. Implemented "
                                + "enumerations: %s",
                        name, orNone(implemented)));
            }
        }
    }

    private void checkNamesAreNotShared(List<String> problems) {
        for (String name : resolved.keySet()) {
            if (properties.getTasks().containsKey(name)) {
                problems.add(String.format(
                        "\"%s\" is configured both under ludwig.reconciliation.tasks and under "
                                + "ludwig.reconciliation.enumerations. The two kinds share run-lock "
                                + "names and the actuator's task selector, so one would be operated "
                                + "on when the other was meant. Rename one of them.", name));
            }
        }
    }

    private void checkReferences(EnumerationSettings settings, List<String> problems) {
        settings.rateLimitName().ifPresent(limit -> {
            if (!properties.getRateLimits().containsKey(limit)) {
                problems.add(String.format(
                        "Enumeration \"%s\" references rate limit \"%s\", which is not configured. "
                                + "Its walks would call the partner as fast as walk.max-concurrency "
                                + "allows. Configured rate limits: %s",
                        settings.name(), limit, orNone(properties.getRateLimits().keySet())));
            }
        });
        settings.restClientName().ifPresent(client -> {
            if (!restClientExists.test(client)) {
                problems.add(String.format(
                        "Enumeration \"%s\" names REST client \"%s\", which is not configured under "
                                + "ludwig.rest-client.clients (or the rest-client starter is not on the "
                                + "classpath). Its feed would fail on its first call, and only then.",
                        settings.name(), client));
            }
        });
    }

    private void checkIncremental(EnumerationSettings settings,
                                  EnumerationTask<?, ?, ?, ?> bean,
                                  List<String> problems) {
        if (bean == null || !settings.isIncremental()) {
            return;
        }
        if (!bean.feed().supportsChangedSince()) {
            problems.add(String.format(
                    "Enumeration \"%s\" sets refresh.incremental-interval (%s) but its feed %s does not "
                            + "declare supportsChangedSince(). Every incremental walk would be a full "
                            + "listing the engine believes is a delta: it would never sweep, and the "
                            + "partner would be called for everything at the incremental cadence. "
                            + "Remove the interval, or implement the bound and declare it.",
                    settings.name(), settings.incrementalInterval(), bean.feed().getClass().getName()));
        }
    }

    private void checkSweep(EnumerationSettings settings, List<String> problems) {
        double fraction = settings.sweepMaxFraction();
        if (!(fraction > 0.0 && fraction <= 1.0)) {
            problems.add(String.format(
                    "Enumeration \"%s\" has sweep.max-fraction %s, which must be greater than 0 and at "
                            + "most 1. At 0 or below no member could ever be reported as gone; above 1 "
                            + "the guard against a partner answering with an empty list is off.",
                    settings.name(), fraction));
        }
        if (settings.sweepConfirmAfterMisses() < 1) {
            problems.add(String.format(
                    "Enumeration \"%s\" has sweep.confirm-after-misses %d, which must be at least 1. "
                            + "Below 1 a member would be reported as gone by the walk that listed it.",
                    settings.name(), settings.sweepConfirmAfterMisses()));
        }
    }

    private static ReconciliationEnumeration annotationOf(EnumerationTask<?, ?, ?, ?> bean) {
        return org.springframework.core.annotation.AnnotationUtils.findAnnotation(
                org.springframework.aop.support.AopUtils.getTargetClass(bean), ReconciliationEnumeration.class);
    }

    private static String orNone(Collection<String> names) {
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }
}
