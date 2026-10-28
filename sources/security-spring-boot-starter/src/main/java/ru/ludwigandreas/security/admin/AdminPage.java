package ru.ludwigandreas.security.admin;

import java.util.List;
import java.util.function.Function;

/**
 * One page of an administrative listing.
 *
 * @param items the rows of this page
 * @param next  the cursor of the following page, or null when this is the last
 * @param <T>   the row type
 */
public record AdminPage<T>(List<T> items, String next) {

    /**
     * Copies the rows.
     */
    public AdminPage {
        items = List.copyOf(items);
    }

    /**
     * Builds a page from a read of one row more than the page size - the cheap way to know whether another page
     * exists without counting.
     *
     * @param fetched  up to {@code pageSize + 1} rows, in listing order
     * @param pageSize the page size the read was made for
     * @param cursorOf the cursor that resumes after a given row
     * @param <T>      the row type
     * @return the first {@code pageSize} rows, with a cursor when a further row was read
     */
    public static <T> AdminPage<T> of(List<T> fetched, int pageSize, Function<T, String> cursorOf) {
        if (fetched.size() <= pageSize) {
            return new AdminPage<>(fetched, null);
        }
        List<T> page = fetched.subList(0, pageSize);
        return new AdminPage<>(page, cursorOf.apply(page.get(pageSize - 1)));
    }
}
