package ru.ludwigandreas.jira.api;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.error.JiraException;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.issue.BulkCreateResult;
import ru.ludwigandreas.jira.model.issue.CreateMeta;
import ru.ludwigandreas.jira.model.issue.CreatedIssue;
import ru.ludwigandreas.jira.model.issue.EditMeta;
import ru.ludwigandreas.jira.model.issue.Issue;
import ru.ludwigandreas.jira.model.issue.Transition;
import ru.ludwigandreas.jira.model.user.JiraUser;
import ru.ludwigandreas.jira.request.IssueInput;
import ru.ludwigandreas.jira.request.NotifyInput;
import ru.ludwigandreas.jira.request.TransitionInput;

/**
 * Issues: create, read, update, delete, assign, transition, and the metadata that says what any of those
 * will accept.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#issues()}.
 */
public final class IssueApi {

    private static final String ISSUE = ApiPaths.API_2 + "/issue";

    private final JiraRestClient rest;

    public IssueApi(JiraRestClient rest) {
        this.rest = rest;
    }

    /**
     * Creates an issue.
     *
     * <p>Not retried on a transport failure, and that is not an oversight: a create whose response was lost
     * may well have succeeded, and re-sending it produces a duplicate. Jira offers no idempotency key to
     * make this safe. A caller that must be certain should search for the issue it was creating before
     * retrying by hand.
     *
     * @param input the fields to set
     * @return the created issue's identity
     */
    public CreatedIssue create(IssueInput input) {
        return rest.post(ISSUE).operation("issue.create").body(input).as(CreatedIssue.class);
    }

    /**
     * Creates several issues in one request.
     *
     * <p>Does <em>not</em> throw when some of them fail. Jira answers a partially successful bulk create
     * with a 201, and the failures are reported per element in the result - so a caller that ignores
     * {@link BulkCreateResult#hasFailures()} loses issues silently. Check it.
     *
     * @param inputs the issues to create
     * @return the created issues and the per-element failures
     */
    public BulkCreateResult createAll(List<IssueInput> inputs) {
        return rest.post(ISSUE + "/bulk")
                .operation("issue.create.bulk")
                .body(Map.of("issueUpdates", inputs))
                .as(BulkCreateResult.class);
    }

    /** Reads an issue with Jira's default field set. */
    public Issue get(String issueKeyOrId) {
        return get(issueKeyOrId, List.of(), List.of());
    }

    /**
     * Reads an issue.
     *
     * @param issueKeyOrId the issue key or numeric id
     * @param fields field ids to return; empty means Jira's default navigable set
     * @param expand expansions such as {@code names}, {@code schema}, {@code changelog}, {@code renderedFields}
     * @return the issue
     */
    public Issue get(String issueKeyOrId, Collection<String> fields, Collection<String> expand) {
        return rest.get(JiraPaths.of(ISSUE, issueKeyOrId))
                .operation("issue.get")
                .queryJoined("fields", fields)
                .queryJoined("expand", expand)
                .as(Issue.class);
    }

    /** Reads an issue, answering empty rather than throwing when it does not exist or is not visible. */
    public Optional<Issue> find(String issueKeyOrId) {
        return rest.get(JiraPaths.of(ISSUE, issueKeyOrId)).operation("issue.get").asOptional(Issue.class);
    }

    /**
     * Reads an issue with the field metadata needed to decode its custom fields without a second call.
     *
     * @param issueKeyOrId the issue key or numeric id
     * @return the issue, expanded with {@code names} and {@code schema}
     */
    public Issue getWithFieldMetadata(String issueKeyOrId) {
        return get(issueKeyOrId, List.of(), List.of("names", "schema"));
    }

    /**
     * Updates an issue.
     *
     * @param issueKeyOrId the issue key or numeric id
     * @param input the changes; fields not mentioned are left alone
     */
    public void update(String issueKeyOrId, IssueInput input) {
        update(issueKeyOrId, input, true);
    }

    /**
     * Updates an issue, choosing whether Jira sends its notification emails.
     *
     * <p>Suppressing notifications needs the project-admin or Jira-admin right; without it Jira rejects the
     * whole update rather than ignoring the flag. Worth using for a bulk migration, which would otherwise
     * mail every watcher once per issue.
     *
     * @param issueKeyOrId the issue key or numeric id
     * @param input the changes
     * @param notifyUsers whether to send notification emails
     */
    public void update(String issueKeyOrId, IssueInput input, boolean notifyUsers) {
        if (input.isEmpty()) {
            throw new JiraException("Refusing to send an empty update for " + issueKeyOrId
                    + ": Jira answers a payload with no fields and no operations with a 400");
        }
        rest.put(JiraPaths.of(ISSUE, issueKeyOrId))
                .operation("issue.update")
                .query("notifyUsers", notifyUsers ? null : Boolean.FALSE)
                .body(input)
                .asVoid();
    }

    /**
     * Deletes an issue.
     *
     * @param issueKeyOrId the issue key or numeric id
     * @param deleteSubtasks whether to delete its subtasks too; Jira refuses the delete when an issue has
     *     subtasks and this is false
     */
    public void delete(String issueKeyOrId, boolean deleteSubtasks) {
        rest.delete(JiraPaths.of(ISSUE, issueKeyOrId))
                .operation("issue.delete")
                .query("deleteSubtasks", deleteSubtasks)
                .asVoid();
    }

    /**
     * Assigns an issue.
     *
     * @param issueKeyOrId the issue key or numeric id
     * @param username the Jira Server username, or {@code null} to unassign
     */
    public void assign(String issueKeyOrId, String username) {
        rest.put(JiraPaths.of(ISSUE, issueKeyOrId, "assignee"))
                .operation("issue.assign")
                .body(JiraUser.named(username))
                .asVoid();
    }

    /** Assigns an issue to whoever the project's default-assignee rule names. */
    public void assignToDefault(String issueKeyOrId) {
        assign(issueKeyOrId, "-1");
    }

    /**
     * The transitions available on this issue, for this user, right now.
     *
     * @param issueKeyOrId the issue key or numeric id
     * @param withFields whether to expand each transition's screen fields, which is what says which of them
     *     are required
     * @return the available transitions
     */
    public List<Transition> transitions(String issueKeyOrId, boolean withFields) {
        Map<String, List<Transition>> wrapper = rest.get(JiraPaths.of(ISSUE, issueKeyOrId, "transitions"))
                .operation("issue.transitions")
                .query("expand", withFields ? "transitions.fields" : null)
                .as(new TypeReference<Map<String, List<Transition>>>() { });
        return wrapper.getOrDefault("transitions", List.of());
    }

    /** Executes a transition by id. */
    public void transition(String issueKeyOrId, String transitionId) {
        transition(issueKeyOrId, TransitionInput.of(transitionId));
    }

    /** Executes a transition, applying field changes and comments atomically with it. */
    public void transition(String issueKeyOrId, TransitionInput input) {
        rest.post(JiraPaths.of(ISSUE, issueKeyOrId, "transitions"))
                .operation("issue.transition")
                .body(input)
                .asVoid();
    }

    /**
     * Executes a transition looked up by name on this issue.
     *
     * <p>The right way to transition from code. Transition ids belong to a workflow, not to Jira, so an id
     * that works in one project is a different transition - or nothing at all - in another; a name at least
     * fails loudly. The lookup costs one extra request and is matched case-insensitively.
     *
     * @param issueKeyOrId the issue key or numeric id
     * @param transitionName the transition's display name, for example {@code Resolve Issue}
     * @param input field changes to apply with the transition, or {@code null} for none
     * @throws JiraException when no transition of that name is available on this issue
     */
    public void transitionByName(String issueKeyOrId, String transitionName, IssueInput input) {
        List<Transition> available = transitions(issueKeyOrId, false);
        Transition match = available.stream()
                .filter(transition -> transitionName.equalsIgnoreCase(transition.name()))
                .findFirst()
                .orElseThrow(() -> new JiraException("No transition named '" + transitionName + "' is available on "
                        + issueKeyOrId + "; available: " + available.stream().map(Transition::name).toList()));
        transition(issueKeyOrId, input == null
                ? TransitionInput.of(match.id())
                : TransitionInput.of(match.id(), input));
    }

    /** Which fields the calling user may edit on this issue, on the screen that applies to it. */
    public EditMeta editMeta(String issueKeyOrId) {
        return rest.get(JiraPaths.of(ISSUE, issueKeyOrId, "editmeta"))
                .operation("issue.editmeta")
                .as(EditMeta.class);
    }

    /**
     * Which fields a create needs, for one project and one issue type.
     *
     * <p>Scoped rather than instance-wide on purpose: an unscoped, fully expanded {@code createmeta} on a
     * large instance returns tens of megabytes and can take minutes.
     *
     * @param projectKey the project to create in
     * @param issueTypeName the issue type to create
     * @return the create metadata, with each issue type's fields expanded
     */
    public CreateMeta createMeta(String projectKey, String issueTypeName) {
        return rest.get(ISSUE + "/createmeta")
                .operation("issue.createmeta")
                .query("projectKeys", projectKey)
                .query("issuetypeNames", issueTypeName)
                .query("expand", "projects.issuetypes.fields")
                .as(CreateMeta.class);
    }

    /** Sends an ad-hoc email about an issue, respecting the project's browse permissions. */
    public void notifyUsers(String issueKeyOrId, NotifyInput input) {
        rest.post(JiraPaths.of(ISSUE, issueKeyOrId, "notify"))
                .operation("issue.notify")
                .body(input)
                .asVoid();
    }

    /** Archives an issue. Requires Jira Data Center; Jira Server answers 404 for this endpoint. */
    public void archive(String issueKeyOrId) {
        rest.put(JiraPaths.of(ISSUE, issueKeyOrId, "archive")).operation("issue.archive").asVoid();
    }

    /** Restores an archived issue. Requires Jira Data Center. */
    public void restore(String issueKeyOrId) {
        rest.put(JiraPaths.of(ISSUE, issueKeyOrId, "restore")).operation("issue.restore").asVoid();
    }
}
