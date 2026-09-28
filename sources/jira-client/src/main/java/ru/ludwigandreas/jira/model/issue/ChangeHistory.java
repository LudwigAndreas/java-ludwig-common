package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.OffsetDateTime;
import java.util.List;
import ru.ludwigandreas.jira.model.user.JiraUser;

/** One entry of an issue's change history: who changed what, when, in a single edit. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChangeHistory(String id, JiraUser author, OffsetDateTime created, List<ChangeItem> items) {

    /** Normalizes {@code items} to an immutable empty list rather than {@code null}. */
    public ChangeHistory {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** The changes in this entry that touched the named field, by field name as Jira records it. */
    public List<ChangeItem> itemsFor(String fieldName) {
        return items.stream().filter(item -> fieldName.equals(item.field())).toList();
    }
}
