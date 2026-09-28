package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.ludwigandreas.jira.model.common.ItemList;
import ru.ludwigandreas.jira.model.common.Progress;
import ru.ludwigandreas.jira.model.common.TimeTracking;
import ru.ludwigandreas.jira.model.common.Votes;
import ru.ludwigandreas.jira.model.common.Watches;
import ru.ludwigandreas.jira.model.project.ProjectComponent;
import ru.ludwigandreas.jira.model.project.ProjectRef;
import ru.ludwigandreas.jira.model.project.ProjectVersion;
import ru.ludwigandreas.jira.model.user.JiraUser;

/**
 * The {@code fields} object of an issue: Jira's system fields as typed accessors, plus every custom field
 * as raw JSON.
 *
 * <p>This is the one model type in the client that is a class rather than a record, and the reason is
 * structural. An issue's field set is open: it is the system fields plus whatever custom fields the
 * instance defines, under keys like {@code customfield_10234} that no compiled type can enumerate. A record
 * cannot both declare typed components and absorb arbitrary extra keys, so the split is explicit here -
 * typed accessors for what Jira guarantees, {@link #customFields()} for what the instance adds.
 *
 * <p>Custom field values stay as {@link JsonNode} rather than being coerced to {@code Object}. Decoding
 * them to a real type is the job of {@link ru.ludwigandreas.jira.field.FieldAccess}, which knows the field's
 * schema and can therefore turn {@code {"value":"Blue","id":"10100"}} into an option and
 * {@code [{"name":"ops"}]} into a list of groups. A caller that guesses from the JSON shape alone gets it
 * wrong for exactly the fields that matter.
 *
 * <p>Every accessor may return {@code null}: which fields are populated depends entirely on the
 * {@code fields} parameter of the request that produced the issue. Asking for {@code fields=summary,status}
 * and then reading {@code assignee()} yields {@code null}, and that is not distinguishable here from an
 * unassigned issue - the distinction lives in what was requested.
 */
@JsonAutoDetect(
        fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE,
        setterVisibility = JsonAutoDetect.Visibility.NONE,
        creatorVisibility = JsonAutoDetect.Visibility.NONE)
public final class IssueFields {

    private String summary;
    private String description;
    private String environment;
    private IssueType issuetype;
    private ProjectRef project;
    private Status status;
    private Priority priority;
    private Resolution resolution;
    private IssueSecurityLevel security;
    private JiraUser assignee;
    private JiraUser reporter;
    private JiraUser creator;
    private OffsetDateTime created;
    private OffsetDateTime updated;
    private OffsetDateTime resolutiondate;
    private OffsetDateTime lastViewed;
    private LocalDate duedate;
    private List<String> labels;
    private List<ProjectComponent> components;
    private List<ProjectVersion> fixVersions;
    private List<ProjectVersion> versions;
    private List<Attachment> attachment;
    private List<IssueLink> issuelinks;
    private List<IssueRef> subtasks;
    private IssueRef parent;
    private ItemList<Comment> comment;
    private ItemList<Worklog> worklog;
    private TimeTracking timetracking;
    private Long timeoriginalestimate;
    private Long timeestimate;
    private Long timespent;
    private Long aggregatetimeoriginalestimate;
    private Long aggregatetimeestimate;
    private Long aggregatetimespent;
    private Integer workratio;
    private Progress progress;
    private Progress aggregateprogress;
    private Watches watches;
    private Votes votes;

    /**
     * Ignored as a property in its own right: it is reached through the any-getter and any-setter pair
     * below, and leaving it visible would publish a field literally named "other" to Jira.
     */
    @JsonIgnore
    private final Map<String, JsonNode> other = new LinkedHashMap<>();

    /** Issue summary. */
    public String summary() {
        return summary;
    }

    /** Issue description, in Jira wiki markup. */
    public String description() {
        return description;
    }

    /** The {@code environment} field, in Jira wiki markup. */
    public String environment() {
        return environment;
    }

    /** Issue type. */
    public IssueType issueType() {
        return issuetype;
    }

    /** The project the issue belongs to. */
    public ProjectRef project() {
        return project;
    }

    /** Current workflow status. */
    public Status status() {
        return status;
    }

    /** Priority. */
    public Priority priority() {
        return priority;
    }

    /** Resolution, {@code null} while the issue is unresolved. */
    public Resolution resolution() {
        return resolution;
    }

    /** Issue security level, {@code null} when the issue is not restricted. */
    public IssueSecurityLevel security() {
        return security;
    }

    /** Assignee, {@code null} when unassigned. */
    public JiraUser assignee() {
        return assignee;
    }

    /** Reporter. */
    public JiraUser reporter() {
        return reporter;
    }

    /** Creator, which differs from the reporter when the issue was raised on someone else's behalf. */
    public JiraUser creator() {
        return creator;
    }

    /** Creation timestamp. */
    public OffsetDateTime created() {
        return created;
    }

    /** Last modification timestamp. */
    public OffsetDateTime updated() {
        return updated;
    }

    /** Resolution timestamp, {@code null} while the issue is unresolved. */
    public OffsetDateTime resolutionDate() {
        return resolutiondate;
    }

    /** When the calling user last viewed the issue. */
    public OffsetDateTime lastViewed() {
        return lastViewed;
    }

    /** Due date, which Jira stores as a day with no time or zone. */
    public LocalDate dueDate() {
        return duedate;
    }

    /** Labels, never {@code null} once the field was requested. */
    public List<String> labels() {
        return labels == null ? List.of() : labels;
    }

    /** Components. */
    public List<ProjectComponent> components() {
        return components == null ? List.of() : components;
    }

    /** Fix versions. */
    public List<ProjectVersion> fixVersions() {
        return fixVersions == null ? List.of() : fixVersions;
    }

    /** Affects versions. */
    public List<ProjectVersion> affectsVersions() {
        return versions == null ? List.of() : versions;
    }

    /** Attachments. */
    public List<Attachment> attachments() {
        return attachment == null ? List.of() : attachment;
    }

    /** Issue links. */
    public List<IssueLink> issueLinks() {
        return issuelinks == null ? List.of() : issuelinks;
    }

    /** Subtasks. */
    public List<IssueRef> subtasks() {
        return subtasks == null ? List.of() : subtasks;
    }

    /** Parent issue, populated for a subtask and for an issue in a parent/child hierarchy. */
    public IssueRef parent() {
        return parent;
    }

    /** Embedded comments; check {@link ItemList#isTruncated()} before treating it as complete. */
    public ItemList<Comment> comments() {
        return comment;
    }

    /** Embedded worklogs; check {@link ItemList#isTruncated()} before treating it as complete. */
    public ItemList<Worklog> worklogs() {
        return worklog;
    }

    /** The {@code timetracking} composite. */
    public TimeTracking timeTracking() {
        return timetracking;
    }

    /** Original estimate in seconds. */
    public Long originalEstimateSeconds() {
        return timeoriginalestimate;
    }

    /** Remaining estimate in seconds. */
    public Long remainingEstimateSeconds() {
        return timeestimate;
    }

    /** Time logged in seconds. */
    public Long timeSpentSeconds() {
        return timespent;
    }

    /** Original estimate in seconds, including subtasks. */
    public Long aggregateOriginalEstimateSeconds() {
        return aggregatetimeoriginalestimate;
    }

    /** Remaining estimate in seconds, including subtasks. */
    public Long aggregateRemainingEstimateSeconds() {
        return aggregatetimeestimate;
    }

    /** Time logged in seconds, including subtasks. */
    public Long aggregateTimeSpentSeconds() {
        return aggregatetimespent;
    }

    /** Jira's work ratio: time spent against original estimate, as a percentage, or -1 when unestimated. */
    public Integer workRatio() {
        return workratio;
    }

    /** Progress on this issue alone. */
    public Progress progress() {
        return progress;
    }

    /** Progress including subtasks. */
    public Progress aggregateProgress() {
        return aggregateprogress;
    }

    /** Watch state. */
    public Watches watches() {
        return watches;
    }

    /** Vote state. */
    public Votes votes() {
        return votes;
    }

    /**
     * Every field Jira returned that is not one of the typed accessors above: custom fields under their
     * {@code customfield_NNNNN} keys, and any system field a future Jira version adds.
     *
     * @return an unmodifiable view, in the order Jira sent them
     */
    @JsonAnyGetter
    public Map<String, JsonNode> customFields() {
        return Collections.unmodifiableMap(other);
    }

    /**
     * The raw JSON of one field by its API id.
     *
     * <p>Returns an empty optional both when the field is absent and when Jira sent an explicit JSON null,
     * which is how it reports a custom field that exists on the screen but has no value - a distinction no
     * caller has ever needed and that a present-but-null {@code JsonNode} would force everyone to handle.
     *
     * @param fieldId the API id, for example {@code customfield_10234}
     * @return the value, or empty when unset
     */
    public Optional<JsonNode> raw(String fieldId) {
        JsonNode node = other.get(fieldId);
        return node == null || node.isNull() ? Optional.empty() : Optional.of(node);
    }

    /** Whether Jira returned a value for this field id at all, including an explicit null. */
    public boolean has(String fieldId) {
        return other.containsKey(fieldId);
    }

    @JsonAnySetter
    private void put(String key, JsonNode value) {
        other.put(key, value);
    }

    @Override
    public String toString() {
        return "IssueFields[summary=" + summary + ", status=" + status + ", custom=" + other.keySet() + "]";
    }
}
