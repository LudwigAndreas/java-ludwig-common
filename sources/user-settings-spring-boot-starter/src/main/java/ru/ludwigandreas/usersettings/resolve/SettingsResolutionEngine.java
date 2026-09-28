package ru.ludwigandreas.usersettings.resolve;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.OrderComparator;
import ru.ludwigandreas.usersettings.api.ResolvedSettings;
import ru.ludwigandreas.usersettings.api.ResolvedValue;
import ru.ludwigandreas.usersettings.api.ScopedValue;
import ru.ludwigandreas.usersettings.api.SettingDefinition;
import ru.ludwigandreas.usersettings.api.SettingLayer;
import ru.ludwigandreas.usersettings.api.SettingScope;
import ru.ludwigandreas.usersettings.api.SettingScopeResolver;
import ru.ludwigandreas.usersettings.api.SettingValueSource;
import ru.ludwigandreas.usersettings.api.SettingsSubject;
import ru.ludwigandreas.usersettings.api.SettingValueConverter;
import ru.ludwigandreas.usersettings.metrics.SettingsMetrics;
import ru.ludwigandreas.usersettings.registry.SettingDefinitionRegistry;

/**
 * Turns "who is this subject" into "every setting, resolved, with its layer" - in one pass and one
 * database query.
 *
 * <h2>The shape of the algorithm, and why it is this shape</h2>
 *
 * <p>Every scope that could supply a value is computed <em>before</em> anything is fetched: the
 * subject, each of their roles, their tenant, the platform. Because the whole set is known up front,
 * the sources can be asked once for all of them, which is what collapses resolution to a single
 * query. The naive version, walking the layers from most specific to least and stopping at the first
 * hit, looks cheaper and is not: it issues a query per layer per setting, which is the N+1 this
 * module exists not to have.
 *
 * <p>Precedence is then applied in memory, against the scope order rather than against the layer
 * alone, so that two roles setting the same key resolve deterministically to the first role the
 * resolver listed.
 *
 * <p>Conversion happens last, and only for the candidate that won. A row whose encoding no longer
 * matches its definition, which is what a setting whose type changed between releases looks like, is
 * skipped and the next candidate down is tried - so one bad row costs that one setting its most
 * specific layer rather than failing the whole resolution. That fallback is the reason this returns
 * a complete set even in a half-migrated deployment.
 */
@Slf4j
public class SettingsResolutionEngine {

    private final SettingDefinitionRegistry registry;
    private final List<SettingScopeResolver> scopeResolvers;
    private final List<SettingValueSource> sources;
    private final SettingsMetrics metrics;

    /** Sorts the resolvers and sources once, so precedence cannot drift with bean iteration order. */
    public SettingsResolutionEngine(SettingDefinitionRegistry registry,
                                    List<SettingScopeResolver> scopeResolvers,
                                    List<SettingValueSource> sources,
                                    SettingsMetrics metrics) {
        this.registry = registry;
        // Layer order first, then the resolver's own order within a layer. Sorting here rather than
        // at every resolution means the precedence of the whole deployment is decided once, at
        // startup, and cannot drift with bean iteration order.
        List<SettingScopeResolver> ordered = new ArrayList<>(scopeResolvers);
        ordered.sort(Comparator
                .comparingInt((SettingScopeResolver resolver) -> resolver.layer().ordinal())
                .thenComparingInt(SettingScopeResolver::getOrder));
        this.scopeResolvers = List.copyOf(ordered);

        List<SettingValueSource> orderedSources = new ArrayList<>(sources);
        orderedSources.sort(OrderComparator.INSTANCE);
        this.sources = List.copyOf(orderedSources);
        this.metrics = metrics;
    }

    /** Every scope that could supply a value for this subject, most specific first. */
    public List<SettingScope> scopesFor(SettingsSubject subject) {
        List<SettingScope> scopes = new ArrayList<>();
        Set<SettingScope> seen = new HashSet<>();
        for (SettingScopeResolver resolver : scopeResolvers) {
            for (SettingScope scope : resolver.scopesFor(subject)) {
                // A duplicate would give one scope two ranks, and which one applied would depend on
                // which entry the precedence map happened to be built from last.
                if (seen.add(scope)) {
                    scopes.add(scope);
                }
            }
        }
        return scopes;
    }

    /** Every declared setting for this subject, resolved against one fetch of every scope. */
    public ResolvedSettings resolve(SettingsSubject subject) {
        long startedAt = System.nanoTime();

        List<SettingScope> scopes = scopesFor(subject);
        Map<SettingScope, Integer> rank = new HashMap<>();
        for (int index = 0; index < scopes.size(); index++) {
            rank.put(scopes.get(index), index);
        }

        Map<String, List<ScopedValue>> candidates = collectCandidates(subject, scopes, rank);

        Map<String, ResolvedValue<?>> resolved = new LinkedHashMap<>();
        for (SettingDefinition<?> definition : registry.definitions()) {
            resolved.put(definition.getKey(), resolveOne(definition, candidates.get(definition.getKey())));
        }

        metrics.recordResolution(Duration.ofNanos(System.nanoTime() - startedAt), resolved.size());
        return new ResolvedSettings(subject, resolved);
    }

    /**
     * Merges every source's answer into one candidate list per setting, ordered by scope precedence.
     *
     * <p>The first source to supply a given (scope, key) wins, which is how a stored row beats a
     * configured default at the same scope without either source having to know the other exists.
     */
    private Map<String, List<ScopedValue>> collectCandidates(SettingsSubject subject,
                                                             List<SettingScope> scopes,
                                                             Map<SettingScope, Integer> rank) {
        Map<String, List<ScopedValue>> candidates = new HashMap<>();
        Set<String> claimed = new HashSet<>();
        for (SettingValueSource source : sources) {
            for (ScopedValue value : source.load(subject, scopes)) {
                if (!rank.containsKey(value.scope())) {
                    // A source answered for a scope nobody asked about. Ignoring it is the only safe
                    // response: it has no rank, so there is no defensible place to put it.
                    log.debug("Ignoring value for {} at unrequested scope {}", value.key(), value.scope());
                    continue;
                }
                if (claimed.add(value.scope() + " " + value.key())) {
                    candidates.computeIfAbsent(value.key(), key -> new ArrayList<>()).add(value);
                }
            }
        }
        candidates.values().forEach(list -> list.sort(Comparator.comparingInt(value -> rank.get(value.scope()))));
        return candidates;
    }

    private <T> ResolvedValue<T> resolveOne(SettingDefinition<T> definition, List<ScopedValue> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ResolvedValue<>(definition.getDefaultValue(), SettingLayer.DEFAULT, SettingScope.GLOBAL_ID);
        }
        SettingValueConverter<T> converter = registry.converterFor(definition);
        for (ScopedValue candidate : candidates) {
            if (!converter.typeId().equals(candidate.typeId())) {
                // Written under a different encoding than this build reads. Coercing would silently
                // change the value's meaning; skipping lets a layer that is still readable answer.
                log.warn("Skipping {} at {}: stored as '{}' but this build reads '{}'",
                        definition.getKey(), candidate.scope(), candidate.typeId(), converter.typeId());
                metrics.recordUnreadableValue(candidate.scope().layer());
                continue;
            }
            try {
                T value = converter.fromStorage(candidate.rawValue());
                return new ResolvedValue<>(value, candidate.scope().layer(), candidate.scope().scopeId());
            } catch (RuntimeException e) {
                // Only the exception's TYPE is logged, never its message. A parser's message routinely
                // quotes what it failed on - "Unknown time-zone ID: Mars/Olympus" - so logging it would
                // put the value in the log for precisely the settings that must not be there. The key
                // and the scope are enough to find the row.
                log.warn("Skipping unreadable value for {} at {}: {}",
                        definition.getKey(), candidate.scope(), e.getClass().getSimpleName());
                metrics.recordUnreadableValue(candidate.scope().layer());
            }
        }
        return new ResolvedValue<>(definition.getDefaultValue(), SettingLayer.DEFAULT, SettingScope.GLOBAL_ID);
    }
}
