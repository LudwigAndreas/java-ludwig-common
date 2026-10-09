package ru.ludwigandreas.webcore.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import ru.ludwigandreas.webcore.web.PageResponse;

/** The envelope's job is to be a stable wire shape, so its six members are pinned exactly. */
class PageResponseTest {

    @Test
    @DisplayName("wraps a Spring Data page without leaking its own shape")
    void wrapsPage() {
        PageResponse<String> response = PageResponse.of(
                new PageImpl<>(List.of("a", "b"), PageRequest.of(1, 2), 7));

        assertThat(response.content()).containsExactly("a", "b");
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.offset()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(7);
        assertThat(response.totalPages()).isEqualTo(4);
    }

    @Test
    @DisplayName("maps the page's contents in one call, so a controller cannot forget to")
    void mapsWhileWrapping() {
        PageResponse<Integer> response = PageResponse.of(
                new PageImpl<>(List.of("a", "bb"), PageRequest.of(0, 2), 2), String::length);

        assertThat(response.content()).containsExactly(1, 2);
        assertThat(response.totalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("maps an already-built envelope while keeping its paging members")
    void mapsAfterWrapping() {
        PageResponse<Integer> response = PageResponse.of(
                        new PageImpl<>(List.of("a", "bb"), PageRequest.of(3, 2), 9))
                .map(String::length);

        assertThat(response.content()).containsExactly(1, 2);
        assertThat(response.page()).isEqualTo(3);
        assertThat(response.totalElements()).isEqualTo(9);
    }

    @Test
    @DisplayName("an unpaginated endpoint reports one full page")
    void wrapsAFullList() {
        PageResponse<String> response = PageResponse.ofAll(List.of("a", "b", "c"));

        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(3);
        assertThat(response.totalElements()).isEqualTo(3);
        assertThat(response.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty result is a page of zero, not a page of nothing")
    void wrapsEmptyResults() {
        assertThat(PageResponse.empty(20).totalPages()).isZero();
        assertThat(PageResponse.empty(20).size()).isEqualTo(20);
        assertThat(PageResponse.ofAll(List.of()).totalPages()).isZero();
    }

    @Test
    @DisplayName("the content list is copied, so the envelope cannot be changed after the fact")
    void copiesContent() {
        List<String> mutable = new java.util.ArrayList<>(List.of("a"));
        PageResponse<String> response = new PageResponse<>(mutable, 0, 1, 0L, 1L, 1);

        mutable.add("b");

        assertThat(response.content()).containsExactly("a");
        assertThatThrownBy(() -> response.content().add("c"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a null content list is an empty page rather than a serialization failure")
    void toleratesNullContent() {
        assertThat(new PageResponse<String>(null, 0, 20, 0L, 0L, 0).content()).isEmpty();
    }

    @Test
    @DisplayName("an offset that is not a multiple of the size is reported as the offset it was given")
    void reportsAnUnalignedOffset() {
        PageResponse<String> response = PageResponse.of(List.of("a", "b"), 25, 20, 100L);

        assertThat(response.offset()).isEqualTo(25);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalPages()).isEqualTo(5);
    }

    @Test
    @DisplayName("two unaligned offsets that share a page number are still told apart")
    void distinguishesOffsetsSharingAPage() {
        PageResponse<String> at20 = PageResponse.of(List.of("a"), 20, 20, 100L);
        PageResponse<String> at25 = PageResponse.of(List.of("b"), 25, 20, 100L);

        assertThat(at20.page()).isEqualTo(at25.page());
        assertThat(at20.offset()).isNotEqualTo(at25.offset());
    }

    @Test
    @DisplayName("a declined count is an absent total, not a total of zero")
    void declinedCountHasNoTotal() {
        PageResponse<String> response = PageResponse.of(List.of("a", "b"), 0, 20, null);

        assertThat(response.totalElements()).isNull();
        assertThat(response.totalPages()).isNull();
        assertThat(response.hasTotal()).isFalse();
    }

    @Test
    @DisplayName("an absent total is omitted from the JSON rather than serialized as null")
    void absentTotalIsOmittedFromJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        String declined = mapper.writeValueAsString(PageResponse.of(List.of("a"), 0, 20, null));
        String counted = mapper.writeValueAsString(PageResponse.of(List.of("a"), 0, 20, 1L));

        assertThat(declined).doesNotContain("totalElements").doesNotContain("totalPages");
        assertThat(counted).contains("\"totalElements\":1").contains("\"totalPages\":1");
        assertThat(declined).contains("\"offset\":0");
    }

    @Test
    @DisplayName("ofAll and empty start at offset zero")
    void unpaginatedEnvelopesStartAtZero() {
        assertThat(PageResponse.ofAll(List.of("a", "b")).offset()).isZero();
        assertThat(PageResponse.empty(20).offset()).isZero();
    }
}
