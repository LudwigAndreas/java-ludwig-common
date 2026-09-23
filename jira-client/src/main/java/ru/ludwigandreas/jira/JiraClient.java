package ru.ludwigandreas.jira;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.jira.api.AttachmentApi;
import ru.ludwigandreas.jira.api.CommentApi;
import ru.ludwigandreas.jira.api.ComponentApi;
import ru.ludwigandreas.jira.api.FieldApi;
import ru.ludwigandreas.jira.api.FilterApi;
import ru.ludwigandreas.jira.api.GroupApi;
import ru.ludwigandreas.jira.api.IssueApi;
import ru.ludwigandreas.jira.api.IssueLinkApi;
import ru.ludwigandreas.jira.api.IssuePropertyApi;
import ru.ludwigandreas.jira.api.MetadataApi;
import ru.ludwigandreas.jira.api.PermissionApi;
import ru.ludwigandreas.jira.api.ProjectApi;
import ru.ludwigandreas.jira.api.RemoteIssueLinkApi;
import ru.ludwigandreas.jira.api.SearchApi;
import ru.ludwigandreas.jira.api.ServerApi;
import ru.ludwigandreas.jira.api.UserApi;
import ru.ludwigandreas.jira.api.VersionApi;
import ru.ludwigandreas.jira.api.VoteApi;
import ru.ludwigandreas.jira.api.WatcherApi;
import ru.ludwigandreas.jira.api.WorklogApi;
import ru.ludwigandreas.jira.auth.JiraCredentials;
import ru.ludwigandreas.jira.error.JiraException;
import ru.ludwigandreas.jira.field.CustomFieldRegistry;
import ru.ludwigandreas.jira.field.FieldAccess;
import ru.ludwigandreas.jira.field.IssueBinder;
import ru.ludwigandreas.jira.model.server.ServerInfo;
import ru.ludwigandreas.jira.structure.api.ForestApi;
import ru.ludwigandreas.jira.structure.api.StructureApi;
import ru.ludwigandreas.jira.structure.api.StructureItemApi;
import ru.ludwigandreas.jira.structure.api.StructureValueApi;

/**
 * A client for the Jira Server REST API v2, targeting Jira Server 9.12.27 (buildNumber 9120027), and for
 * the ALM Works Structure 2.0 REST API.
 *
 * <pre>{@code
 * try (JiraClient client = JiraClient.builder()
 *         .baseUrl("https://jira.example.com")
 *         .personalAccessToken(System.getenv("JIRA_TOKEN"))
 *         .verifyOnBuild(true)
 *         .build()) {
 *
 *     client.search()
 *             .searchAll(JqlQuery.builder()
 *                     .where(Jql.project().is("OPS"))
 *                     .where(Jql.status().notIn("Done"))
 *                     .orderByStableWalk()
 *                     .build())
 *             .forEach(issue -> log.info("{} {}", issue.key(), issue.fieldsOrEmpty().summary()));
 * }
 * }</pre>
 *
 * <p>Thread-safe and intended to be long-lived: one instance per Jira instance per application, held for
 * the process's lifetime. Building one opens no connection - the transport pools them - but building one
 * per request throws that pooling away, and with
 * {@link JiraClientBuilder#loadCustomFields(boolean)} also re-reads the field catalogue every time.
 *
 * <p>Deliberately not a Spring bean and with no Spring dependency. A service that wants it injected declares
 * one {@code @Bean} method; see the module README.
 *
 * <p>Every accessor below returns a stable instance - they are not created per call - so holding
 * {@code client.issues()} in a field is fine. {@link #rest()} is the escape hatch to any endpoint this
 * client does not model, with the same authentication, retries, metrics and error mapping.
 */
public final class JiraClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JiraClient.class);

    private final JiraRestClient rest;
    private final JiraCredentials credentials;

    private final IssueApi issues;
    private final SearchApi search;
    private final CommentApi comments;
    private final WorklogApi worklogs;
    private final AttachmentApi attachments;
    private final IssueLinkApi issueLinks;
    private final WatcherApi watchers;
    private final VoteApi votes;
    private final RemoteIssueLinkApi remoteLinks;
    private final IssuePropertyApi issueProperties;
    private final ProjectApi projects;
    private final VersionApi versions;
    private final ComponentApi components;
    private final UserApi users;
    private final GroupApi groups;
    private final FieldApi fields;
    private final MetadataApi metadata;
    private final FilterApi filters;
    private final PermissionApi permissions;
    private final ServerApi server;
    private final StructureApi structures;
    private final ForestApi forests;
    private final StructureValueApi structureValues;
    private final StructureItemApi structureItems;

    private volatile CustomFieldRegistry customFieldRegistry;
    private volatile FieldAccess fieldAccess;
    private volatile IssueBinder binder;

    JiraClient(JiraRestClient rest, JiraCredentials credentials, CustomFieldRegistry customFieldRegistry) {
        this.rest = rest;
        this.credentials = credentials;
        this.customFieldRegistry = customFieldRegistry;
        this.issues = new IssueApi(rest);
        this.search = new SearchApi(rest);
        this.comments = new CommentApi(rest);
        this.worklogs = new WorklogApi(rest);
        this.attachments = new AttachmentApi(rest);
        this.issueLinks = new IssueLinkApi(rest, issues);
        this.watchers = new WatcherApi(rest);
        this.votes = new VoteApi(rest);
        this.remoteLinks = new RemoteIssueLinkApi(rest);
        this.issueProperties = new IssuePropertyApi(rest);
        this.projects = new ProjectApi(rest);
        this.versions = new VersionApi(rest);
        this.components = new ComponentApi(rest);
        this.users = new UserApi(rest);
        this.groups = new GroupApi(rest);
        this.fields = new FieldApi(rest);
        this.metadata = new MetadataApi(rest);
        this.filters = new FilterApi(rest);
        this.permissions = new PermissionApi(rest);
        this.server = new ServerApi(rest);
        this.forests = new ForestApi(rest);
        this.structures = new StructureApi(rest);
        this.structureValues = new StructureValueApi(rest);
        this.structureItems = new StructureItemApi(rest, forests);
    }

    /** Starts building a client. */
    public static JiraClientBuilder builder() {
        return new JiraClientBuilder();
    }

    /** Issues: create, read, update, delete, assign, transition. */
    public IssueApi issues() {
        return issues;
    }

    /** JQL search. */
    public SearchApi search() {
        return search;
    }

    /** Comments. */
    public CommentApi comments() {
        return comments;
    }

    /** Worklogs. */
    public WorklogApi worklogs() {
        return worklogs;
    }

    /** Attachments. */
    public AttachmentApi attachments() {
        return attachments;
    }

    /** Issue links and link types. */
    public IssueLinkApi issueLinks() {
        return issueLinks;
    }

    /** Watchers. */
    public WatcherApi watchers() {
        return watchers;
    }

    /** Votes. */
    public VoteApi votes() {
        return votes;
    }

    /** Remote issue links. */
    public RemoteIssueLinkApi remoteLinks() {
        return remoteLinks;
    }

    /** Entity properties on issues. */
    public IssuePropertyApi issueProperties() {
        return issueProperties;
    }

    /** Projects, and their components, versions and roles. */
    public ProjectApi projects() {
        return projects;
    }

    /** Project versions. */
    public VersionApi versions() {
        return versions;
    }

    /** Project components. */
    public ComponentApi components() {
        return components;
    }

    /** Users. */
    public UserApi users() {
        return users;
    }

    /** Groups and their membership. */
    public GroupApi groups() {
        return groups;
    }

    /** Field definitions. */
    public FieldApi fields() {
        return fields;
    }

    /** Issue types, statuses, priorities and resolutions. */
    public MetadataApi metadata() {
        return metadata;
    }

    /** Saved filters. */
    public FilterApi filters() {
        return filters;
    }

    /** What the calling user is allowed to do. */
    public PermissionApi permissions() {
        return permissions;
    }

    /** Instance version, features and licensed applications. */
    public ServerApi server() {
        return server;
    }

    /** Structure structures. Requires the ALM Works Structure app. */
    public StructureApi structures() {
        return structures;
    }

    /** Structure forest content. Requires the ALM Works Structure app. */
    public ForestApi forests() {
        return forests;
    }

    /** Structure computed column values. Requires the ALM Works Structure app. */
    public StructureValueApi structureValues() {
        return structureValues;
    }

    /** Structure items such as folders. Requires the ALM Works Structure app. */
    public StructureItemApi structureItems() {
        return structureItems;
    }

    /** The low-level REST client, for endpoints this library does not model. */
    public JiraRestClient rest() {
        return rest;
    }

    /**
     * The instance's field catalogue, loaded on first use and then held.
     *
     * <p>Loaded lazily rather than at construction so that building a client makes no network call unless
     * asked - see {@link JiraClientBuilder#loadCustomFields(boolean)} to change that. Never refreshed: a
     * custom field added to Jira afterwards is not visible to this client until it is rebuilt.
     *
     * @return the registry
     */
    public CustomFieldRegistry customFields() {
        CustomFieldRegistry local = customFieldRegistry;
        if (local == null) {
            synchronized (this) {
                local = customFieldRegistry;
                if (local == null) {
                    local = CustomFieldRegistry.load(fields);
                    customFieldRegistry = local;
                }
            }
        }
        return local;
    }

    /** Decodes custom field values off an issue, using the field catalogue. */
    public FieldAccess fieldAccess() {
        FieldAccess local = fieldAccess;
        if (local == null) {
            synchronized (this) {
                local = fieldAccess;
                if (local == null) {
                    local = new FieldAccess(rest.json(), customFields());
                    fieldAccess = local;
                }
            }
        }
        return local;
    }

    /** Projects issues onto application types annotated with {@code @JiraFieldBinding}. */
    public IssueBinder binder() {
        IssueBinder local = binder;
        if (local == null) {
            synchronized (this) {
                local = binder;
                if (local == null) {
                    local = new IssueBinder(rest.json(), customFields());
                    binder = local;
                }
            }
        }
        return local;
    }

    /**
     * Confirms the instance is reachable, the credentials work, and the deployment is what this client
     * targets.
     *
     * <p>Two calls: {@code /serverInfo}, which most instances answer anonymously and therefore separates
     * "wrong URL" from "wrong credentials", and {@code /myself}, which only authenticated callers reach.
     * A Jira older than the targeted build, or a Cloud deployment behind the URL, is logged as a warning
     * rather than rejected - this client mostly works against both, and refusing to start would be a worse
     * failure than saying so.
     *
     * @return the instance's server information
     * @throws JiraException when the instance is unreachable or the credentials are rejected
     */
    public ServerInfo verifyConnection() {
        ServerInfo info = server.info();
        String user = Objects.toString(users.myself().name(), "unknown");
        log.info("Connected to Jira {} (build {}) at {} as '{}' using {}",
                info.version(), info.buildNumber(), rest.baseUri(), user, credentials.describe());
        if (!info.isAtLeastTargetBuild()) {
            log.warn("This Jira reports build {}, older than the {} this client was verified against; "
                            + "endpoints and payload shapes may differ",
                    info.buildNumber(), ServerInfo.TARGET_BUILD_NUMBER);
        }
        return info;
    }

    /** Releases the transport's resources. Safe to call more than once. */
    @Override
    public void close() {
        rest.close();
    }
}
