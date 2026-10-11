package ru.ludwigandreas.reconciliation.engine.enumeration;

import ru.ludwigandreas.reconciliation.config.EnumerationSettings;
import ru.ludwigandreas.reconciliation.entity.ScopeMemberStatus;
import ru.ludwigandreas.reconciliation.entity.SyncScopeMember;
import ru.ludwigandreas.reconciliation.entity.SyncScopeState;
import ru.ludwigandreas.reconciliation.repository.SyncScopeMemberRepository;
import ru.ludwigandreas.reconciliation.repository.SyncScopeStateRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What an operator can see and do about enumerations, as plain maps.
 *
 * <p>The actuator endpoint's one collaborator for the second task kind. It exists so that the
 * endpoint can be handed enumerations through a single optional reference instead of a longer
 * constructor, and so that nothing in the endpoint has to know how a walk is driven.
 *
 * <p>Whether a destructive action is permitted, and who is asking, stay with the endpoint: those are
 * questions about the caller, and this class is about the enumeration.
 */
public class EnumerationOperations {

    private static final int SAMPLE = 20;

    private final EnumerationRegistry registry;
    private final EnumerationRunner runner;
    private final EnumerationRuntime runtime;
    private final SyncScopeStateRepository states;
    private final SyncScopeMemberRepository members;

    /**
     * Creates the operations.
     *
     * @param registry the enumerations
     * @param runner   drives passes and scopes
     * @param runtime  the scheduled passes and the freshness figure
     * @param states   scope state
     * @param members  membership
     */
    public EnumerationOperations(EnumerationRegistry registry,
                                 EnumerationRunner runner,
                                 EnumerationRuntime runtime,
                                 SyncScopeStateRepository states,
                                 SyncScopeMemberRepository members) {
        this.registry = registry;
        this.runner = runner;
        this.runtime = runtime;
        this.states = states;
        this.members = members;
    }

    /**
     * Whether a name is an enumeration.
     *
     * @param name a task name from the endpoint's selector
     * @return true if it names an enumeration
     */
    public boolean knows(String name) {
        return registry.find(name).isPresent();
    }

    /** Every enumeration's name. */
    public List<String> names() {
        return registry.names();
    }

    /** Every scheduled enumeration pass. */
    public List<String> passNames() {
        return runtime.passNames();
    }

    /** One summary per enumeration, for the endpoint's overview. */
    public List<Map<String, Object>> summaries() {
        return registry.all().stream().map(enumeration -> summarize(enumeration.settings())).toList();
    }

    /**
     * An enumeration's settings, its least fresh scopes and a sample of its quarantined members.
     *
     * @param name the enumeration
     * @return the detail view
     */
    public Map<String, Object> detail(String name) {
        EnumerationSettings settings = require(name).settings();
        Map<String, Object> view = new LinkedHashMap<>(summarize(settings));
        view.put("walk", Map.of(
                "pageSize", settings.pageSize(),
                "maxConcurrency", settings.maxConcurrency(),
                "maxScopesPerRun", settings.maxScopesPerRun()));
        view.put("sweep", Map.of(
                "confirmAfterMisses", settings.sweepConfirmAfterMisses(),
                "maxFraction", settings.sweepMaxFraction()));
        view.put("retry", Map.of(
                "maxAttempts", settings.retry().maxAttempts(),
                "initialInterval", settings.retry().backoff().initialInterval().toString(),
                "maxInterval", settings.retry().backoff().maxInterval().toString()));
        view.put("scopesSample", states.sample(name, SAMPLE).stream()
                .map(EnumerationOperations::describe).toList());
        view.put("quarantinedSample", members.sample(name, ScopeMemberStatus.QUARANTINED, SAMPLE).stream()
                .map(EnumerationOperations::describe).toList());
        return view;
    }

    /**
     * One scope's walk state and membership counts.
     *
     * @param name     the enumeration
     * @param scopeKey the encoded scope key
     * @return the scope view
     */
    public Map<String, Object> scope(String name, String scopeKey) {
        require(name);
        SyncScopeState state = states.findScope(name, scopeKey).orElseThrow(() ->
                new IllegalArgumentException("Enumeration '" + name + "' has no scope '" + scopeKey + "'"));
        Map<String, Object> view = new LinkedHashMap<>(describe(state));
        view.put("members", members.countMembers(name, scopeKey));
        return view;
    }

    /**
     * A task-level action.
     *
     * @param name   the enumeration
     * @param action {@code run}, {@code discover} or {@code requeue-members}
     * @return what happened
     */
    public Map<String, Object> operate(String name, String action) {
        RegisteredEnumeration<?, ?, ?, ?> enumeration = require(name);
        return switch (action) {
            case "run" -> pass(name, "walk");
            case "discover" -> pass(name, "discover");
            case "requeue-members" -> {
                long requeued = runner.requeueMembers(enumeration);
                yield requeued < 0 ? busy() : Map.of("task", name, "requeued", requeued,
                        "note", "attempt counters reset; the scopes that held them are due for a "
                                + "full walk, which is the only kind certain to list them again");
            }
            default -> throw new IllegalArgumentException("Unknown action '" + action + "' for an "
                    + "enumeration. Actions: run, discover, requeue-members. Scope actions are sent "
                    + "to the scope: walk, preview, reset-scope, discard-sweep, confirm-sweep");
        };
    }

    /**
     * A scope-level action. The caller has already checked that a destructive one is permitted.
     *
     * @param name     the enumeration
     * @param scopeKey the encoded scope key
     * @param action   {@code walk}, {@code preview}, {@code reset-scope}, {@code discard-sweep} or
     *                 {@code confirm-sweep}
     * @param kind     for {@code walk}: {@code full} to force a full walk, otherwise the planner
     *                 chooses
     * @return what happened
     */
    public Map<String, Object> operateScope(String name, String scopeKey, String action, String kind) {
        RegisteredEnumeration<?, ?, ?, ?> enumeration = require(name);
        return switch (action) {
            case "walk" -> runner.walkScope(enumeration, scopeKey, "full".equalsIgnoreCase(kind))
                    .map(EnumerationOperations::describe).orElseGet(EnumerationOperations::busy);
            case "preview" -> runner.preview(enumeration, scopeKey);
            case "reset-scope" -> runner.resetScope(enumeration, scopeKey)
                    ? Map.of("task", name, "scope", scopeKey,
                    "note", "cursor and watermark cleared, membership kept; the next walk is a full one")
                    : busy();
            case "discard-sweep" -> runner.discardSweep(enumeration, scopeKey)
                    ? Map.of("task", name, "scope", scopeKey,
                    "note", "nothing was reported as gone; the scope is due for a full walk")
                    : busy();
            case "confirm-sweep" -> runner.confirmSweep(enumeration, scopeKey)
                    .<Map<String, Object>>map(result -> Map.of("task", name, "scope", scopeKey,
                            "candidates", result.candidates(), "reported", result.reported()))
                    .orElseGet(EnumerationOperations::busy);
            default -> throw new IllegalArgumentException("Unknown scope action '" + action
                    + "'. Actions: walk, preview, reset-scope, discard-sweep, confirm-sweep");
        };
    }

    /**
     * Whether a scope action needs the destructive-operations setting and an explicit confirmation.
     *
     * @param action the scope action
     * @return true for the one action that reports members as gone on an operator's word
     */
    public static boolean isDestructive(String action) {
        return "confirm-sweep".equals(action);
    }

    private Map<String, Object> pass(String name, String pass) {
        String passName = name + ":" + pass;
        if (!runtime.passNames().contains(passName)) {
            throw new IllegalStateException("Enumeration '" + name + "' is disabled; it has no passes");
        }
        boolean ran = runtime.runNow(passName);
        return Map.of("pass", passName, "ran", ran,
                "note", ran ? "completed" : "a run was already in progress; nothing was started");
    }

    private Map<String, Object> summarize(EnumerationSettings settings) {
        String name = settings.name();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", name);
        view.put("kind", "enumeration");
        view.put("enabled", settings.enabled());
        view.put("schedule", settings.schedule().isCron()
                ? settings.schedule().cron() : settings.schedule().fixedDelay().toString());
        view.put("fullInterval", settings.fullInterval().toString());
        view.put("incrementalInterval", settings.incrementalIntervalOrEmpty().map(Object::toString).orElse(null));
        // The figure the SLO alert fires on, shown next to the intervals it is measured against.
        view.put("freshnessLagSeconds", runtime.freshnessLagSeconds(name).orElse(0.0));
        view.put("scopes", states.countScopes(name));
        view.put("sweepsHeld", states.countHeldSweeps(name));
        view.put("membersFailed", members.countByStatus(name, ScopeMemberStatus.FAILED));
        view.put("membersQuarantined", members.countByStatus(name, ScopeMemberStatus.QUARANTINED));
        view.put("rateLimit", settings.rateLimit());
        return view;
    }

    private static Map<String, Object> describe(SyncScopeState state) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("scope", state.getScopeKey());
        view.put("generation", state.getGeneration());
        view.put("walkInProgress", state.getWalkKind());
        view.put("cursor", state.getWalkCursor());
        view.put("watermark", state.getWatermark());
        view.put("lastFullAt", state.getLastFullAt());
        view.put("lastListedAt", state.getLastIncrementalAt());
        view.put("nextDueAt", state.getNextDueAt());
        view.put("sweepHeldAt", state.getSweepHeldAt());
        view.put("sweepHeldCount", state.getSweepHeldCount());
        view.put("lastError", state.getLastError());
        return view;
    }

    private static Map<String, Object> describe(SyncScopeMember member) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("scope", member.getScopeKey());
        view.put("member", member.getMemberKey());
        view.put("attempts", member.getAttempts());
        view.put("lastError", member.getLastError());
        view.put("updatedAt", member.getUpdatedAt());
        return view;
    }

    private static Map<String, Object> describe(WalkReport report) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("scope", report.scopeKey());
        view.put("status", report.status());
        view.put("kind", report.kind() == null ? null : report.kind().name().toLowerCase(Locale.ROOT));
        view.put("applied", report.members().applied());
        view.put("unchanged", report.members().unchanged());
        view.put("skipped", report.members().skipped());
        view.put("failed", report.members().failed());
        view.put("sweep", Optional.ofNullable(report.sweep()).map(sweep -> Map.of(
                "verdict", sweep.verdict(), "candidates", sweep.candidates(),
                "reported", sweep.reported())).orElse(null));
        view.put("error", report.error());
        return view;
    }

    private static Map<String, Object> busy() {
        return Map.of("ran", false, "note", "a run is in progress on another instance; nothing was done");
    }

    private RegisteredEnumeration<?, ?, ?, ?> require(String name) {
        return registry.find(name).orElseThrow(() -> new IllegalArgumentException(
                "No such enumeration: '" + name + "'. Enumerations: " + String.join(", ", registry.names())));
    }
}
