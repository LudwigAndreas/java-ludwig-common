package ru.ludwigandreas.security.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.security.admin.AdminCursor;
import ru.ludwigandreas.security.admin.AdminPage;
import ru.ludwigandreas.security.admin.InvalidAdminRequestException;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

class AdminCursorTest {

    @Test
    @DisplayName("a cursor round-trips its key parts, including empty ones")
    void roundTrips() {
        String cursor = AdminCursor.encode("2026-10-28T09:00:00Z", "", "7f3c");

        assertThat(cursor).doesNotContain("=", "+", "/");
        assertThat(AdminCursor.decode(cursor, 3)).containsExactly("2026-10-28T09:00:00Z", "", "7f3c");
    }

    @Test
    @DisplayName("a value that is not a cursor of the expected shape is a 400, not the first page")
    void refusesTamperedCursors() {
        String twoParts = AdminCursor.encode("a", "b");

        for (String bad : List.of("not base64!", twoParts.substring(0, twoParts.length() - 2), twoParts)) {
            assertThatThrownBy(() -> AdminCursor.decode(bad, 3))
                    .as(bad)
                    .isInstanceOfSatisfying(InvalidAdminRequestException.class, e -> {
                        assertThat(e.toDefinition().code()).isEqualTo(InvalidAdminRequestException.CURSOR_INVALID);
                        assertThat(e.toDefinition().status()).isEqualTo(ProblemStatus.INVALID);
                    });
        }
    }

    @Test
    @DisplayName("the page size defaults, and is refused outside 1..200")
    void pageSize() {
        assertThat(AdminCursor.pageSize(null)).isEqualTo(AdminCursor.DEFAULT_PAGE_SIZE);
        assertThat(AdminCursor.pageSize(AdminCursor.MAX_PAGE_SIZE)).isEqualTo(AdminCursor.MAX_PAGE_SIZE);
        for (int bad : new int[] {0, -1, AdminCursor.MAX_PAGE_SIZE + 1, 1000}) {
            assertThatThrownBy(() -> AdminCursor.pageSize(bad))
                    .isInstanceOfSatisfying(InvalidAdminRequestException.class, e -> assertThat(
                            e.toDefinition().code()).isEqualTo(InvalidAdminRequestException.PAGE_SIZE_INVALID));
        }
    }

    @Test
    @DisplayName("a page read one row long carries a cursor resuming after its last row")
    void pages() {
        AdminPage<String> more = AdminPage.of(List.of("a", "b", "c"), 2, row -> "after-" + row);
        AdminPage<String> last = AdminPage.of(List.of("a", "b"), 2, row -> "after-" + row);

        assertThat(more.items()).containsExactly("a", "b");
        assertThat(more.next()).isEqualTo("after-b");
        assertThat(last.items()).containsExactly("a", "b");
        assertThat(last.next()).isNull();
    }
}
