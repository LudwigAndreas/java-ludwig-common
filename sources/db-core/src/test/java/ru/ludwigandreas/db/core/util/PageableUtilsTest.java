package ru.ludwigandreas.db.core.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageableUtilsTest {

    @Test
    void clampsSizeToMax() {
        Pageable pageable = PageableUtils.of(0, 1000, 100, null, null);
        assertThat(pageable.getPageSize()).isEqualTo(100);
    }

    @Test
    void clampsSizeToAtLeastOne() {
        Pageable pageable = PageableUtils.of(0, 0, 100, null, null);
        assertThat(pageable.getPageSize()).isEqualTo(1);
    }

    @Test
    void appliesDefaultSortWhenNoneGiven() {
        Sort defaultSort = Sort.by("name").ascending();
        Pageable pageable = PageableUtils.of(0, 20, 100, null, defaultSort);
        assertThat(pageable.getSort()).isEqualTo(defaultSort);
    }

    @Test
    void requestedSortOverridesDefaultSort() {
        Sort requested = Sort.by("age").descending();
        Sort defaultSort = Sort.by("name").ascending();
        Pageable pageable = PageableUtils.of(0, 20, 100, requested, defaultSort);
        assertThat(pageable.getSort()).isEqualTo(requested);
    }

    @Test
    void rejectsNegativePage() {
        assertThatThrownBy(() -> PageableUtils.of(-1, 20, 100, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
