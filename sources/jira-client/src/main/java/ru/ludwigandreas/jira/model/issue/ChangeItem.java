package ru.ludwigandreas.jira.model.issue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One field change inside a {@link ChangeHistory} entry.
 *
 * <p>The JSON property named {@code toString} is exposed here as {@link #toDisplayValue()}: a record
 * component cannot be called {@code toString} without colliding with {@link Object#toString()}, so the
 * mapping is declared with {@link JsonProperty} instead. {@link #fromDisplayValue()} is renamed to match,
 * for symmetry.
 *
 * <p>{@code from}/{@code to} hold the raw identifiers (a status id, a username) and the display pair holds
 * what a human saw. Compare on the raw values; show the display ones.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChangeItem(String field,
                         String fieldtype,
                         String fieldId,
                         String from,
                         @JsonProperty("fromString") String fromDisplayValue,
                         String to,
                         @JsonProperty("toString") String toDisplayValue) {
}
