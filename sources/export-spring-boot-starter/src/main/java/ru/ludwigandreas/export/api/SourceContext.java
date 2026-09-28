package ru.ludwigandreas.export.api;

import com.querydsl.core.types.Predicate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Everything a {@link RowSource} is told about the run it is opening a stream for.
 *
 * <h2>Why the source receives a predicate and not a filter string</h2>
 *
 * <p>The requester's {@code $filter} has already been parsed by {@code odata-filter} against the
 * definition's own {@code FilterPolicy}, and the requester's data scope has already been resolved by
 * {@code security}. Both arrive here as one {@link com.querydsl.core.types.Predicate} that the
 * source conjoins into its query.
 *
 * <p>Handing the source a string instead would mean every source parsed it, which is how a platform
 * ends up with one filter dialect per report and a scope check that one of them forgot. Handing it a
 * typed predicate makes the scope non-optional in the only way that matters: there is no signature
 * here that returns rows without it.
 *
 * @param runId         the run this stream belongs to, for the source's own logging
 * @param parameters    the definition's typed, already-validated parameter object
 * @param predicate     the conjunction of the requester's data scope and their parsed filter; empty
 *                      only when the requester's scope is unrestricted and they filtered on nothing
 * @param sort          the requested order, already validated against the source's sortable columns.
 *                      The source appends its own primary key; see {@link SortKey}
 * @param pageSize      how many rows to fetch per keyset page
 * @param locale        the caller's locale, for a source that has to resolve text itself
 * @param zone          the timezone the run presents instants in
 * @param principalId   the requester, for the source's own audit if it keeps one
 * @param authorities   the requester's authorities <em>as re-resolved at execution time</em>, not as
 *                      they stood when the run was requested
 * @param correlationId the correlation id every log line and partner call on this run carries
 * @param <P>           the definition's parameter type
 */
public record SourceContext<P>(
        UUID runId,
        P parameters,
        Optional<Predicate> predicate,
        List<SortKey> sort,
        int pageSize,
        Locale locale,
        ZoneId zone,
        String principalId,
        Set<String> authorities,
        String correlationId) {

    // SUPPRESS CHECKSTYLE ParameterNumber - a record's canonical constructor. The components are
    // named at every call site by construction, which is the readability the rule protects.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public SourceContext {
        if (runId == null) {
            throw new IllegalArgumentException("A SourceContext needs a run id");
        }
        if (pageSize < 1) {
            throw new IllegalArgumentException("SourceContext page size must be at least 1, was: " + pageSize);
        }
        if (locale == null || zone == null) {
            throw new IllegalArgumentException("A SourceContext needs both a locale and a zone");
        }
        predicate = predicate == null ? Optional.empty() : predicate;
        sort = sort == null ? List.of() : List.copyOf(sort);
        authorities = authorities == null ? Set.of() : Set.copyOf(authorities);
    }
}
