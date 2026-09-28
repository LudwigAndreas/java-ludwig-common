package ru.ludwigandreas.jira.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.jira.error.JiraException;
import ru.ludwigandreas.jira.field.CustomField;
import ru.ludwigandreas.jira.field.CustomFieldRegistry;
import ru.ludwigandreas.jira.field.FieldAccess;
import ru.ludwigandreas.jira.field.FieldValues;
import ru.ludwigandreas.jira.field.IssueBinder;
import ru.ludwigandreas.jira.field.JiraFieldBinding;
import ru.ludwigandreas.jira.json.JiraJson;
import ru.ludwigandreas.jira.model.field.CustomFieldOption;
import ru.ludwigandreas.jira.model.field.FieldDefinition;
import ru.ludwigandreas.jira.model.field.FieldSchema;
import ru.ludwigandreas.jira.model.issue.Issue;

class CustomFieldMappingTest {

    private static final FieldSchema NUMBER_SCHEMA =
            new FieldSchema("number", null, null, "com.example:float", 10004L);

    private final JiraJson json = JiraJson.createDefault();

    private final CustomFieldRegistry registry = CustomFieldRegistry.of(List.of(
            new FieldDefinition("summary", "summary", "Summary", false, true, true, true,
                    List.of("summary"), new FieldSchema("string", null, "summary", null, null)),
            new FieldDefinition("customfield_10004", null, "Story Points", true, true, true, true,
                    List.of("cf[10004]", "Story Points"), NUMBER_SCHEMA),
            new FieldDefinition("customfield_10100", null, "Team", true, true, true, true,
                    List.of("cf[10100]", "Team"),
                    new FieldSchema("option", null, null, "com.example:select", 10100L)),
            new FieldDefinition("customfield_10200", null, "Duplicate", true, true, true, true,
                    List.of(), NUMBER_SCHEMA),
            new FieldDefinition("customfield_10201", null, "Duplicate", true, true, true, true,
                    List.of(), NUMBER_SCHEMA)));

    private Issue issue() {
        byte[] body = ("{\"id\":\"1001\",\"key\":\"OPS-7\",\"fields\":{"
                + "\"summary\":\"Rotate the certificate\","
                + "\"customfield_10004\":8,"
                + "\"customfield_10100\":{\"self\":\"http://x\",\"value\":\"Platform\",\"id\":\"10500\"}"
                + "}}").getBytes(StandardCharsets.UTF_8);
        return json.read(body, Issue.class);
    }

    @Test
    void resolvesADisplayNameToTheInstanceSpecificFieldId() {
        assertThat(registry.requireId("Story Points")).isEqualTo("customfield_10004");
        assertThat(registry.requireId("story points")).isEqualTo("customfield_10004");
    }

    @Test
    void refusesToGuessWhenTwoFieldsShareADisplayName() {
        assertThatThrownBy(() -> registry.requireId("Duplicate"))
                .isInstanceOf(JiraException.class)
                .hasMessageContaining("customfield_10200")
                .hasMessageContaining("customfield_10201");
    }

    @Test
    void reportsAnUnknownNameRatherThanReturningNothing() {
        assertThatThrownBy(() -> registry.requireId("No Such Field"))
                .isInstanceOf(JiraException.class)
                .hasMessageContaining("No Such Field");
    }

    @Test
    void buildsTheJqlClauseNameThatSurvivesARename() {
        assertThat(registry.jqlClauseName("Story Points")).isEqualTo("cf[10004]");
    }

    @Test
    void refusesACustomFieldFormForASystemField() {
        assertThatThrownBy(() -> registry.jqlClauseName("Summary"))
                .isInstanceOf(JiraException.class)
                .hasMessageContaining("not a custom field");
    }

    @Test
    void decodesACustomFieldIntoTheDeclaredType() {
        FieldAccess access = new FieldAccess(json, registry);
        CustomField<Double> storyPoints = registry.field("Story Points", Double.class);

        assertThat(access.read(issue(), storyPoints)).contains(8.0);
    }

    @Test
    void decodesAnOptionValueRatherThanLeavingItAsAMap() {
        FieldAccess access = new FieldAccess(json, registry);

        CustomFieldOption team = access.readOption(issue().fieldsOrEmpty(), "customfield_10100").orElseThrow();

        assertThat(team.value()).isEqualTo("Platform");
        assertThat(team.id()).isEqualTo("10500");
    }

    @Test
    void answersEmptyForAFieldTheIssueDoesNotCarry() {
        FieldAccess access = new FieldAccess(json, registry);

        assertThat(access.readNumber(issue().fieldsOrEmpty(), "customfield_99999")).isEmpty();
    }

    @Test
    void buildsTheMinimalWriteShapeWhichIsNotTheReadShape() {
        assertThat(FieldValues.option("10500")).containsExactly(java.util.Map.entry("id", "10500"));
        assertThat(FieldValues.user("jsmith")).containsExactly(java.util.Map.entry("name", "jsmith"));
        assertThat(FieldValues.cascadingOption("1", "2"))
                .containsEntry("id", "1")
                .containsEntry("child", java.util.Map.of("id", "2"));
    }

    @Test
    void projectsAnIssueOntoAnAnnotatedRecord() {
        IssueBinder binder = new IssueBinder(json, registry);

        Ticket ticket = binder.bind(issue(), Ticket.class);

        assertThat(ticket.key()).isEqualTo("OPS-7");
        assertThat(ticket.summary()).isEqualTo("Rotate the certificate");
        assertThat(ticket.storyPoints()).isEqualTo(8.0);
        assertThat(ticket.team().value()).isEqualTo("Platform");
    }

    @Test
    void leavesAnUnboundPropertyNullRatherThanFailing() {
        IssueBinder binder = new IssueBinder(json, registry);

        assertThat(binder.bind(issue(), PartlyBound.class).dueDate()).isNull();
    }

    @Test
    void rejectsATargetTypeThatDeclaresNoBindings() {
        IssueBinder binder = new IssueBinder(json, registry);

        assertThatThrownBy(() -> binder.bind(issue(), Unannotated.class))
                .isInstanceOf(JiraException.class)
                .hasMessageContaining("no @JiraFieldBinding");
    }

    @Test
    void rejectsABindingThatSetsNeitherIdNorName() {
        IssueBinder binder = new IssueBinder(json, registry);

        assertThatThrownBy(() -> binder.bind(issue(), BadBinding.class))
                .isInstanceOf(JiraException.class)
                .hasMessageContaining("exactly one of id and name");
    }

    record Ticket(@JiraFieldBinding(id = "key") String key,
                  @JiraFieldBinding(id = "summary") String summary,
                  @JiraFieldBinding(name = "Story Points") Double storyPoints,
                  @JiraFieldBinding(name = "Team") CustomFieldOption team) {
    }

    record PartlyBound(@JiraFieldBinding(id = "key") String key,
                       @JiraFieldBinding(id = "duedate") String dueDate) {
    }

    record Unannotated(String key) {
    }

    record BadBinding(@JiraFieldBinding String key) {
    }
}
