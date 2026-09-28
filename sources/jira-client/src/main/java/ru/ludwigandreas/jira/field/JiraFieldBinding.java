package ru.ludwigandreas.jira.field;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds a property of an application type to a Jira field, so an issue can be projected onto a domain
 * object in one call.
 *
 * <p>Declare either an {@link #id()} - a system field name such as {@code summary}, or a
 * {@code customfield_NNNNN} - or a {@link #name()}, which {@link IssueBinder} resolves through a
 * {@link CustomFieldRegistry} at bind time. Prefer {@link #name()} for custom fields: the id differs
 * between environments, and a name resolved at runtime is the same source file in all of them.
 *
 * <pre>{@code
 * public record Ticket(
 *         @JiraFieldBinding(id = "key") String key,
 *         @JiraFieldBinding(id = "summary") String summary,
 *         @JiraFieldBinding(name = "Story Points") Double storyPoints,
 *         @JiraFieldBinding(name = "Team") CustomFieldOption team) {
 * }
 * }</pre>
 *
 * <p>Applicable to record components, fields and constructor parameters, so both records and classes work
 * as targets.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface JiraFieldBinding {

    /**
     * The Jira field id: a system field name, or {@code customfield_NNNNN}. Also accepts {@code key} and
     * {@code id}, which name the issue itself rather than one of its fields.
     *
     * @return the field id, or an empty string when {@link #name()} is used instead
     */
    String id() default "";

    /**
     * The custom field's display name, resolved through the registry at bind time.
     *
     * @return the display name, or an empty string when {@link #id()} is used instead
     */
    String name() default "";
}
