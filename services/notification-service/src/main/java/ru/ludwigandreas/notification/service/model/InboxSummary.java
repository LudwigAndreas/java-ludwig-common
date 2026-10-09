package ru.ludwigandreas.notification.service.model;

/**
 * How many of the caller's items are outstanding.
 *
 * <p>A record rather than a bare {@code long} so that the endpoint's response is a JSON object and
 * can gain a field - a per-category breakdown, a bounded "99+" form - without becoming a breaking
 * change. An endpoint that returns a naked number has no second version.
 *
 * @param unread items that are neither read nor dismissed. Dismissed-without-reading counts as dealt
 *               with: a recipient who swiped a notification away has handled it, and counting it
 *               would leave a badge that cannot be cleared
 */
public record InboxSummary(long unread) {
}
