package ru.ludwigandreas.jira.field;

/**
 * The plugin keys of the custom field types Jira Server ships, as they appear in
 * {@link ru.ludwigandreas.jira.model.field.FieldSchema#custom()}.
 *
 * <p>The key is what says how a field's value is shaped, and the shapes differ in ways no caller can guess
 * from the JSON alone. A single-select sends {@code {"id":"10100"}}; a multi-checkbox sends a list of those;
 * a cascading select nests a {@code child}; a labels field sends bare strings; a user picker sends
 * {@code {"name":"jsmith"}}. Writing the wrong shape is a 400 that names the field and not the mistake.
 *
 * <p>The three Jira Software keys at the end ({@code gh-sprint}, {@code gh-epic-link},
 * {@code gh-lexo-rank}) are included because they turn up on any instance with Jira Software installed and
 * are the fields integrations ask about most, even though the Agile REST API itself is out of this module's
 * scope.
 */
public final class CustomFieldTypes {

    private static final String BASE = "com.atlassian.jira.plugin.system.customfieldtypes:";

    /** Single-line text. Value: a string. */
    public static final String TEXT_FIELD = BASE + "textfield";

    /** Multi-line text. Value: a string. */
    public static final String TEXT_AREA = BASE + "textarea";

    /** Read-only text, set by an app or a post-function. Value: a string. */
    public static final String READ_ONLY_TEXT = BASE + "readonlyfield";

    /** URL field. Value: a string. */
    public static final String URL_FIELD = BASE + "url";

    /** Number field. Value: a JSON number. */
    public static final String FLOAT_FIELD = BASE + "float";

    /** Date picker. Value: {@code "yyyy-MM-dd"}. */
    public static final String DATE_PICKER = BASE + "datepicker";

    /** Date and time picker. Value: a Jira timestamp. */
    public static final String DATE_TIME = BASE + "datetime";

    /** Single select. Value: an option object. */
    public static final String SELECT = BASE + "select";

    /** Radio buttons. Value: an option object. */
    public static final String RADIO_BUTTONS = BASE + "radiobuttons";

    /** Multi select. Value: a list of option objects. */
    public static final String MULTI_SELECT = BASE + "multiselect";

    /** Checkboxes. Value: a list of option objects. */
    public static final String MULTI_CHECKBOXES = BASE + "multicheckboxes";

    /** Cascading select. Value: an option object carrying a nested {@code child} option. */
    public static final String CASCADING_SELECT = BASE + "cascadingselect";

    /** Labels. Value: a list of bare strings, not of objects. */
    public static final String LABELS = BASE + "labels";

    /** Single user picker. Value: {@code {"name": "<username>"}} on Jira Server. */
    public static final String USER_PICKER = BASE + "userpicker";

    /** Multi user picker. Value: a list of user objects. */
    public static final String MULTI_USER_PICKER = BASE + "multiuserpicker";

    /** Group picker. Value: {@code {"name": "<group>"}}. */
    public static final String GROUP_PICKER = BASE + "grouppicker";

    /** Multi group picker. Value: a list of group objects. */
    public static final String MULTI_GROUP_PICKER = BASE + "multigrouppicker";

    /** Single version picker. Value: a version object. */
    public static final String VERSION_PICKER = BASE + "version";

    /** Multi version picker. Value: a list of version objects. */
    public static final String MULTI_VERSION_PICKER = BASE + "multiversion";

    /** Project picker. Value: a project object. */
    public static final String PROJECT_PICKER = BASE + "project";

    /** Single issue picker. Value: an issue object. */
    public static final String ISSUE_PICKER = BASE + "issuetype";

    /** Jira Software sprint field. Value: a list; on older Jira versions, of opaque strings. */
    public static final String SPRINT = "com.pyxis.greenhopper.jira:gh-sprint";

    /** Jira Software epic link. Value: an issue key as a bare string. */
    public static final String EPIC_LINK = "com.pyxis.greenhopper.jira:gh-epic-link";

    /** Jira Software rank. Value: an opaque LexoRank string. */
    public static final String RANK = "com.pyxis.greenhopper.jira:gh-lexo-rank";

    private CustomFieldTypes() {
    }
}
