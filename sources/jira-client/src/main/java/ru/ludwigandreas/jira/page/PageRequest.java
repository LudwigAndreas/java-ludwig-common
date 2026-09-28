package ru.ludwigandreas.jira.page;

/**
 * One window into a paginated Jira collection: where to start, and how many rows to ask for.
 *
 * <p>Jira Server paginates by offset ({@code startAt}/{@code maxResults}), not by cursor - the cursor-based
 * {@code nextPageToken} scheme belongs to Jira Cloud's v3 API and does not exist on 9.12. Offset pagination
 * is not stable under concurrent modification: an issue created while a walk is in progress can shift rows
 * across the page boundary, so a long walk may see a row twice or miss one. Where that matters, order the
 * JQL by an immutable key ({@code ORDER BY created ASC, key ASC}) rather than by {@code updated}.
 *
 * @param startAt zero-based index of the first row to return
 * @param maxResults how many rows to ask for; Jira silently caps this at the instance's configured limit
 */
public record PageRequest(int startAt, int maxResults) {

    /**
     * Jira Server's default page size for issue search, and its hard ceiling for most endpoints unless
     * {@code jira.search.views.default.max} has been raised. Asking for more is not an error - Jira just
     * returns fewer rows than requested, which is why every walk here reads {@code maxResults} back off the
     * response instead of assuming it got what it asked for.
     */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** Validates the window. */
    public PageRequest {
        if (startAt < 0) {
            throw new IllegalArgumentException("startAt must not be negative, was " + startAt);
        }
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be at least 1, was " + maxResults);
        }
    }

    /** The first page, at the default size. */
    public static PageRequest first() {
        return new PageRequest(0, DEFAULT_PAGE_SIZE);
    }

    /** The first page, at an explicit size. */
    public static PageRequest ofSize(int maxResults) {
        return new PageRequest(0, maxResults);
    }

    /** The window immediately after this one, keeping the same size. */
    public PageRequest next() {
        return new PageRequest(startAt + maxResults, maxResults);
    }

    /** The window starting where the given page actually ended, which may differ from {@link #next()}. */
    public PageRequest after(Page<?> page) {
        return new PageRequest(page.startAt() + page.values().size(), maxResults);
    }
}
