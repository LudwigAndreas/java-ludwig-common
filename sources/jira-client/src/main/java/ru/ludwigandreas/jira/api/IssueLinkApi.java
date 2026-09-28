package ru.ludwigandreas.jira.api;

import java.util.List;
import ru.ludwigandreas.jira.JiraRestClient;
import ru.ludwigandreas.jira.http.JiraPaths;
import ru.ludwigandreas.jira.model.issue.IssueLink;
import ru.ludwigandreas.jira.model.issue.IssueLinkType;
import ru.ludwigandreas.jira.request.IssueLinkInput;

/**
 * Links between issues, and the link types an instance defines.
 *
 * <p>Reached through {@link ru.ludwigandreas.jira.JiraClient#issueLinks()}.
 *
 * <p>There is no endpoint that lists an issue's links: they arrive as the {@code issuelinks} field of the
 * issue itself. {@link #of(String)} is that read, spelled here so the whole subject lives in one place.
 */
public final class IssueLinkApi {

    private static final String ISSUE_LINK = ApiPaths.API_2 + "/issueLink";
    private static final String ISSUE_LINK_TYPE = ApiPaths.API_2 + "/issueLinkType";

    private final JiraRestClient rest;
    private final IssueApi issues;

    public IssueLinkApi(JiraRestClient rest, IssueApi issues) {
        this.rest = rest;
        this.issues = issues;
    }

    /** The link types configured on this instance. */
    public List<IssueLinkType> types() {
        return rest.get(ISSUE_LINK_TYPE).operation("issuelinktype.list").as(IssueLinkTypeList.class).issueLinkTypes();
    }

    /** Reads one link type. */
    public IssueLinkType type(String linkTypeId) {
        return rest.get(JiraPaths.of(ISSUE_LINK_TYPE, linkTypeId))
                .operation("issuelinktype.get")
                .as(IssueLinkType.class);
    }

    /** The links on an issue, read from the issue's own {@code issuelinks} field. */
    public List<IssueLink> of(String issueKeyOrId) {
        return issues.get(issueKeyOrId, List.of("issuelinks"), List.of()).fieldsOrEmpty().issueLinks();
    }

    /** Creates a link. Jira answers 201 with no body, so nothing is returned. */
    public void create(IssueLinkInput input) {
        rest.post(ISSUE_LINK).operation("issuelink.create").body(input).asVoid();
    }

    /** Reads one link by id. */
    public IssueLink get(String linkId) {
        return rest.get(JiraPaths.of(ISSUE_LINK, linkId)).operation("issuelink.get").as(IssueLink.class);
    }

    /** Deletes a link. */
    public void delete(String linkId) {
        rest.delete(JiraPaths.of(ISSUE_LINK, linkId)).operation("issuelink.delete").asVoid();
    }

    /** Jira's link-type envelope. */
    private record IssueLinkTypeList(List<IssueLinkType> issueLinkTypes) {

        IssueLinkTypeList {
            issueLinkTypes = issueLinkTypes == null ? List.of() : List.copyOf(issueLinkTypes);
        }
    }
}
