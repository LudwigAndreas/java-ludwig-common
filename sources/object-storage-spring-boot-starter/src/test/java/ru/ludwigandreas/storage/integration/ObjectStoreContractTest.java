package ru.ludwigandreas.storage.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.ludwigandreas.storage.api.ByteRange;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.ObjectSummary;
import ru.ludwigandreas.storage.api.PutOptions;
import ru.ludwigandreas.storage.api.StoredObject;
import ru.ludwigandreas.storage.exception.ObjectNotFoundException;

/**
 * The contract every {@link ObjectStore} satisfies, written once and run against each of them.
 *
 * <h2>Why this is one class and not two suites</h2>
 *
 * <p>The filesystem store exists so that resume behaviour can be tested without a container. That is
 * only worth anything if the two implementations agree, and the cases where they most easily stop
 * agreeing are the ones no one thinks to write twice: a range that runs past the end of the object,
 * a delete of something already gone, a listing that crosses a page boundary. A shared abstract test
 * makes disagreement a build failure rather than a discovery.
 *
 * <p>Subclasses supply a store and a location to use. Everything else, including the fact that a
 * ranged read returns <em>exactly</em> the requested bytes, is asserted here.
 */
abstract class ObjectStoreContractTest {

    /** More objects than one S3 listing page holds, so the continuation token is exercised. */
    private static final int PAGE_CROSSING_COUNT = 12;

    /** Bytes 0..25 as ASCII letters: short enough to assert on, long enough to slice. */
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz";

    @TempDir
    Path work;

    /**
     * The store under test.
     *
     * @return a ready store
     */
    protected abstract ObjectStore store();

    /**
     * A location this store can write to.
     *
     * @param key the object's key, without a leading slash
     * @return the full location in whatever scheme this store serves
     */
    protected abstract String uri(String key);

    /**
     * The maximum number of keys the store returns in one listing page.
     *
     * @return the page size the subclass configured, which must be smaller than
     *         {@link #PAGE_CROSSING_COUNT} for the pagination test to mean anything
     */
    protected abstract int listPageSize();

    @Test
    void roundTripsAnObject() throws IOException {
        String location = uri("round-trip/data.txt");
        StoredObject stored = store().put(location, fileOf("hello"), PutOptions.ofContentType("text/plain"));

        assertThat(stored.size()).isEqualTo(5);
        assertThat(stored.etag()).isNotBlank();
        assertThat(read(store().open(location))).isEqualTo("hello");
    }

    @Test
    void headReportsSizeAndEtagWithoutReadingTheObject() throws IOException {
        String location = uri("head/data.txt");
        store().put(location, fileOf(ALPHABET), PutOptions.none());

        StoredObject head = store().head(location);

        assertThat(head.uri()).isEqualTo(location);
        assertThat(head.size()).isEqualTo(ALPHABET.length());
        assertThat(head.etag()).isNotBlank();
        assertThat(head.lastModified()).isNotNull();
        assertThat(head.contentIdentity()).isEqualTo(head.etag());
    }

    /**
     * The headline property of the ranged read: exactly the requested window, not a byte more.
     *
     * <p>Asserted on the bytes rather than on the length, because an implementation that returned the
     * right number of bytes from the wrong offset would pass a length check and would corrupt every
     * resumed ingest in a way that looks like a parser bug.
     */
    @Test
    void rangedReadReturnsExactlyTheRequestedBytes() throws IOException {
        String location = uri("range/alphabet.txt");
        store().put(location, fileOf(ALPHABET), PutOptions.none());

        assertThat(read(store().open(location, ByteRange.of(0, 4)))).isEqualTo("abcde");
        assertThat(read(store().open(location, ByteRange.of(10, 12)))).isEqualTo("klm");
        assertThat(read(store().open(location, ByteRange.ofLength(23, 3)))).isEqualTo("xyz");
        assertThat(read(store().open(location, ByteRange.from(20)))).isEqualTo("uvwxyz");
    }

    /**
     * A range that starts at or past the end is an empty read, in both implementations.
     *
     * <p>S3 answers 416 and a channel positioned past EOF reads -1; the store is what makes those one
     * behaviour. This is not a curiosity: it is the range a run resumed after having consumed the
     * whole object asks for, which is what a crash between the last batch and the run being marked
     * complete leaves behind.
     */
    @Test
    void rangedReadPastTheEndIsEmptyRatherThanAnError() throws IOException {
        String location = uri("range/short.txt");
        store().put(location, fileOf(ALPHABET), PutOptions.none());

        assertThat(read(store().open(location, ByteRange.from(ALPHABET.length())))).isEmpty();
        assertThat(read(store().open(location, ByteRange.from(ALPHABET.length() + 100)))).isEmpty();
        // A window that starts inside the object and ends past it yields what is there, which is what
        // a batch reader asking for a fixed window near the tail of a file depends on.
        assertThat(read(store().open(location, ByteRange.of(24, 999)))).isEqualTo("yz");
    }

    @Test
    void listsAcrossAContinuationTokenBoundary() {
        for (int i = 0; i < PAGE_CROSSING_COUNT; i++) {
            store().put(uri("page/object-%02d.txt".formatted(i)), fileOf("body-" + i), PutOptions.none());
        }

        List<ObjectSummary> found;
        try (Stream<ObjectSummary> listing = store().list(uri("page/"))) {
            found = listing.toList();
        }

        assertThat(listPageSize()).isLessThan(PAGE_CROSSING_COUNT);
        assertThat(found).hasSize(PAGE_CROSSING_COUNT);
        assertThat(found).extracting(ObjectSummary::name)
                .containsExactly(java.util.stream.IntStream.range(0, PAGE_CROSSING_COUNT)
                        .mapToObj("object-%02d.txt"::formatted)
                        .toArray(String[]::new));
        assertThat(found).allSatisfy(summary -> assertThat(summary.size()).isGreaterThan(0));
    }

    @Test
    void copiesWithoutMovingBytesThroughTheCaller() throws IOException {
        String source = uri("copy/source.txt");
        String target = uri("copy/target.txt");
        store().put(source, fileOf("copy me"), PutOptions.none());

        StoredObject copied = store().copy(source, target);

        assertThat(copied.uri()).isEqualTo(target);
        assertThat(copied.size()).isEqualTo("copy me".length());
        assertThat(read(store().open(target))).isEqualTo("copy me");
        assertThat(store().exists(source)).isTrue();
    }

    @Test
    void deletingSomethingAbsentIsASuccess() {
        String location = uri("delete/never-existed.txt");

        assertThatCode(() -> store().delete(location)).doesNotThrowAnyException();

        store().put(location, fileOf("x"), PutOptions.none());
        store().delete(location);
        assertThat(store().exists(location)).isFalse();
        // Twice, because the whole reason absence is a success is that a half-completed pass is
        // re-run from the start.
        assertThatCode(() -> store().delete(location)).doesNotThrowAnyException();
    }

    @Test
    void openingSomethingMissingRaisesObjectNotFound() {
        String location = uri("missing/nothing-here.txt");

        assertThatThrownBy(() -> store().open(location)).isInstanceOf(ObjectNotFoundException.class);
        assertThatThrownBy(() -> store().open(location, ByteRange.from(0)))
                .isInstanceOf(ObjectNotFoundException.class);
        assertThatThrownBy(() -> store().head(location)).isInstanceOf(ObjectNotFoundException.class);
        assertThat(store().exists(location)).isFalse();
    }

    @Test
    void reportsANewEtagWhenTheContentChangesUnderTheSameKey() {
        String location = uri("etag/catalogue.csv");
        store().put(location, fileOf("id,name\n1,widget"), PutOptions.none());
        String first = store().head(location).contentIdentity();

        // The corrected re-upload: same name, same length, one character different. Keying an
        // exactly-once guard on the name alone would skip this file and lose the correction.
        store().put(location, fileOf("id,name\n1,widgel"), PutOptions.none());

        assertThat(store().head(location).contentIdentity()).isNotEqualTo(first);
    }

    /**
     * Writes a file whose bytes the test then asks the store to hold.
     *
     * @param content the body, written as UTF-8
     * @return the local file
     */
    protected Path fileOf(String content) {
        try {
            Path file = Files.createTempFile(work, "object", ".bin");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Could not stage a test file", e);
        }
    }

    private static String read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
