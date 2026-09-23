package ru.ludwigandreas.jira.api;

import java.util.List;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.JiraRestClient.RequestSpec;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.issue.Worklog;
import ru.ludwigandreas.jira.request.EstimateAdjustment;
import ru.ludwigandreas.jira.request.WorklogInput;

/**
 * Worklogs on an issue.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#worklogs()}.
 *
 * <p>Every write takes an {@link EstimateAdjustment}, because every write changes the issue's remaining
 * estimate unless told otherwise - Jira's default is to subtract the logged time. Importing historical
 * worklogs without passing {@link EstimateAdjustment#leave()} rewrites every estimate in the project.
 */
public final class WorklogApi {

    private static final String ISSUE = ApiPaths.API_2 + "/issue";

    private final JiraRestClient rest;

    public WorklogApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /**
     * Every worklog on an issue.
     *
     * <p>Not paginated here because Jira Server's worklog endpoint answers with the whole collection in one
     * envelope rather than a page.
     *
     * @param issueKeyOrId the issue
     * @return the worklogs, oldest first
     */
    public List<Worklog> list(String issueKeyOrId) {
        return rest.get(JiraPaths.of(ISSUE, issueKeyOrId, "worklog"))
                .operation("worklog.list")
                .as(WorklogList.class)
                .worklogs();
    }

    /** Reads one worklog. */
    public Worklog get(String issueKeyOrId, String worklogId) {
        return rest.get(JiraPaths.of(ISSUE, issueKeyOrId, "worklog", worklogId))
                .operation("worklog.get")
                .as(Worklog.class);
    }

    /**
     * Logs work.
     *
     * @param issueKeyOrId the issue
     * @param input what was done, and when
     * @param adjustment what to do to the remaining estimate
     * @return the stored worklog
     */
    public Worklog add(String issueKeyOrId, WorklogInput input, EstimateAdjustment adjustment) {
        return withAdjustment(rest.post(JiraPaths.of(ISSUE, issueKeyOrId, "worklog")), adjustment)
                .operation("worklog.add")
                .body(input)
                .as(Worklog.class);
    }

    /** Edits a worklog. */
    public Worklog update(String issueKeyOrId, String worklogId, WorklogInput input,
                          EstimateAdjustment adjustment) {
        return withAdjustment(rest.put(JiraPaths.of(ISSUE, issueKeyOrId, "worklog", worklogId)), adjustment)
                .operation("worklog.update")
                .body(input)
                .as(Worklog.class);
    }

    /** Deletes a worklog. */
    public void delete(String issueKeyOrId, String worklogId, EstimateAdjustment adjustment) {
        withAdjustment(rest.delete(JiraPaths.of(ISSUE, issueKeyOrId, "worklog", worklogId)), adjustment)
                .operation("worklog.delete")
                .asVoid();
    }

    private static RequestSpec withAdjustment(RequestSpec spec, EstimateAdjustment adjustment) {
        if (adjustment == null) {
            return spec;
        }
        spec.query("adjustEstimate", adjustment.mode());
        if (adjustment.parameterName() != null) {
            spec.query(adjustment.parameterName(), adjustment.value());
        }
        return spec;
    }

    /** Jira's worklog envelope. */
    private record WorklogList(int startAt, int maxResults, int total, List<Worklog> worklogs) {

        WorklogList {
            worklogs = worklogs == null ? List.of() : List.copyOf(worklogs);
        }
    }
}
