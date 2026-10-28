package ru.ludwigandreas.security.admin;

import ru.ludwigandreas.webcore.problem.LocalizedException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * An administrative request whose paging parameters cannot be honoured.
 *
 * <p>A {@link LocalizedException}, so web-core's pipeline renders it as a {@code 400} problem whose code says which
 * parameter was wrong, without a mapper of its own.
 */
public final class InvalidAdminRequestException extends LocalizedException {

    private static final long serialVersionUID = 1L;

    /** The code of a cursor that does not decode. */
    public static final String CURSOR_INVALID = "ludwig.admin.cursor-invalid";

    /** The code of a page size outside the permitted range. */
    public static final String PAGE_SIZE_INVALID = "ludwig.admin.page-size-invalid";

    private InvalidAdminRequestException(String code, Object... args) {
        super(ProblemStatus.INVALID, code, args);
    }

    static InvalidAdminRequestException cursor() {
        return new InvalidAdminRequestException(CURSOR_INVALID);
    }

    static InvalidAdminRequestException pageSize(int requested) {
        return new InvalidAdminRequestException(PAGE_SIZE_INVALID, requested, AdminCursor.MAX_PAGE_SIZE);
    }
}
