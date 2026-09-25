package ru.ludwigandreas.export.engine;

import java.util.Set;
import java.util.UUID;
import ru.ludwigandreas.export.api.ReportRequest;

/**
 * Turns a request into a plan: the one place a report's scope, columns and formats are decided.
 *
 * <h2>Why this is an interface with one implementation</h2>
 *
 * <p>Not for substitutability - a service is not expected to replace it - but to make the boundary
 * visible. Everything that decides <em>what a requester is allowed to get</em> happens here and
 * nowhere else: the data-scope predicate, the authority re-resolution, the column visibility, the
 * format allowlist. The engine takes a plan and produces a file; it has no code path that could
 * forget a check, because it has no code that performs one.
 *
 * <p>That is also why planning happens per <em>attempt</em> rather than per run. A deferred run
 * re-plans when it starts, which is what re-resolves the requester's authorities at execution time
 * rather than trusting the snapshot taken when the request was accepted.
 */
public interface ExecutionPlanner {

    /**
     * Plans one attempt.
     *
     * @param runId       the run this plan is for
     * @param request     what was asked for
     * @param principalId who asked
     * @param authorities their authorities <em>as of now</em>, not as of the request
     * @param onRequestThread whether this attempt is running on the thread that made the request, which
     *                        is the only case in which a stage may relay the requester's own token.
     *                        Passed in rather than detected, because an empty security context is
     *                        ambiguous - an unauthenticated request looks exactly like a poller thread,
     *                        and only the caller knows which it is
     * @return the plan
     */
    ExecutionPlan<?, ?> plan(UUID runId, ReportRequest request, String principalId,
                             Set<String> authorities, boolean onRequestThread);
}
