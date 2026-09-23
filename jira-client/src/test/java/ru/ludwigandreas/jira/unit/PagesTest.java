package ru.ludwigandreas.jira.unit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.ludwigandreas.jira.page.Page;
import ru.ludwigandreas.jira.page.PageRequest;
import ru.ludwigandreas.jira.page.Pages;

class PagesTest {

    @Test
    void walksEveryPageUsingTheTotalWhenThereIsNoIsLastFlag() {
        List<String> all = Pages.all(window -> pageOf(window, 5, null), 2);

        assertThat(all).containsExactly("0", "1", "2", "3", "4");
    }

    @Test
    void stopsOnAnExplicitIsLastEvenWhenThePageIsFull() {
        Page<String> onlyPage = new Page<>(0, 2, null, Boolean.TRUE, List.of("a", "b"));

        assertThat(Pages.all(window -> onlyPage, 2)).containsExactly("a", "b");
    }

    @Test
    void stopsOnAnEmptyPageEvenIfTheServerStillClaimsThereIsMore() {
        AtomicInteger calls = new AtomicInteger();

        List<String> all = Pages.all(window -> {
            calls.incrementAndGet();
            return new Page<>(window.startAt(), 2, null, Boolean.FALSE, List.of());
        }, 2);

        assertThat(all).isEmpty();
        assertThat(calls).hasValue(1);
    }

    @Test
    void advancesByTheRowsActuallyReturnedNotByTheSizeRequested() {
        // Jira caps maxResults silently; advancing by the requested size would skip the rows it withheld.
        List<Integer> startsSeen = new java.util.ArrayList<>();

        Pages.all(window -> {
            startsSeen.add(window.startAt());
            int cappedSize = 1;
            int index = window.startAt();
            return index < 3
                    ? new Page<>(index, cappedSize, 3, null, List.of(String.valueOf(index)))
                    : new Page<>(index, cappedSize, 3, null, List.of());
        }, 10);

        // Three windows, not four: the known total ends the walk without a final empty request.
        assertThat(startsSeen).containsExactly(0, 1, 2);
    }

    @Test
    void isLazySoAbandoningTheStreamStopsFetching() {
        AtomicInteger calls = new AtomicInteger();

        String first = Pages.<String>stream(window -> {
            calls.incrementAndGet();
            return pageOf(window, 1000, null);
        }, 10).findFirst().orElseThrow();

        assertThat(first).isEqualTo("0");
        assertThat(calls).hasValue(1);
    }

    @Test
    void treatsANegativeTotalAsUnknownRatherThanAsZero() {
        Page<String> page = new Page<>(0, 50, -1, null, List.of("a"));

        assertThat(page.totalCount()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void rejectsAWindowThatCannotBeExpressed() {
        assertThat(PageRequest.first().maxResults()).isEqualTo(PageRequest.DEFAULT_PAGE_SIZE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PageRequest(-1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PageRequest(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Page<String> pageOf(PageRequest window, int total, Boolean isLast) {
        List<String> values = java.util.stream.IntStream
                .range(window.startAt(), Math.min(window.startAt() + window.maxResults(), total))
                .mapToObj(String::valueOf)
                .toList();
        return new Page<>(window.startAt(), window.maxResults(), total, isLast, values);
    }
}
