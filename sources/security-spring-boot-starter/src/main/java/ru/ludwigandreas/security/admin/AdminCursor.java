package ru.ludwigandreas.security.admin;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * The opaque position of a keyset-paginated administrative listing, and the page-size rule every listing shares.
 *
 * <p>A cursor encodes the sort key and id of the last row a page returned. It is opaque so that a client cannot
 * build one, not because it is secret: a cursor edited into another well-formed value only moves the position,
 * and every listing still applies its own authorization and tenant predicates to whatever it reads from there.
 * A value that does not decode is refused with {@code 400} rather than read as "start from the beginning",
 * which would make a truncated cursor silently repeat the first page.
 */
public final class AdminCursor {

    /** The page size when the caller names none. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** The largest page any administrative listing returns. */
    public static final int MAX_PAGE_SIZE = 200;

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "\u001f";

    private AdminCursor() {
    }

    /**
     * Encodes a position.
     *
     * @param keyParts the last row's sort key components and id, in the listing's order
     * @return the opaque cursor
     */
    public static String encode(String... keyParts) {
        String joined = VERSION + SEPARATOR + String.join(SEPARATOR, keyParts);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(joined.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decodes a position written by {@link #encode}.
     *
     * @param cursor the opaque cursor from a previous page
     * @param parts  how many key components the listing encodes
     * @return the components, in the order they were encoded
     * @throws InvalidAdminRequestException when the value is not a cursor of that shape
     */
    public static List<String> decode(String cursor, int parts) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw InvalidAdminRequestException.cursor();
        }
        List<String> split = Arrays.asList(decoded.split(SEPARATOR, -1));
        if (split.size() != parts + 1 || !VERSION.equals(split.get(0))) {
            throw InvalidAdminRequestException.cursor();
        }
        return List.copyOf(split.subList(1, split.size()));
    }

    /**
     * The page size to read, from what the caller asked for.
     *
     * @param requested the caller's {@code size} parameter, or null
     * @return {@link #DEFAULT_PAGE_SIZE} when none was asked for, otherwise the requested size
     * @throws InvalidAdminRequestException when the size is below one or above {@link #MAX_PAGE_SIZE}
     */
    public static int pageSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (requested < 1 || requested > MAX_PAGE_SIZE) {
            throw InvalidAdminRequestException.pageSize(requested);
        }
        return requested;
    }
}
