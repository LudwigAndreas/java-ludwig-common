package ru.ludwigandreas.ingest.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.ingest.config.FileIngestProperties;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.ObjectUri;
import ru.ludwigandreas.storage.api.StoredObject;
import ru.ludwigandreas.storage.exception.ObjectNotFoundException;

/**
 * Decides whether an object is finished being written, before a single byte of it is read.
 *
 * <h2>Why this is the first thing the engine does</h2>
 *
 * <p>Reading a half-written object is the most common way a daily ingest loses data, and it is the
 * worst-behaved failure this module has, because it does not look like a failure. The run reads what
 * is there, parses it, applies it, balances perfectly against what it read, and completes. The tail
 * of the file is simply absent, and nothing anywhere alerts on a successful run. Everything else in
 * this module - the checkpoint, the balance check, the identity constraint - is unable to detect it,
 * because every one of them reasons about the bytes that were there.
 *
 * <p>So there are two mechanisms rather than one, independently configurable, because partners
 * differ and a module that supported only the good one would be unusable with half of them.
 *
 * <h2>Sentinel, which is the default</h2>
 *
 * <p>{@code data.csv} is not touched until {@code data.csv.done} exists. This is the strong form: the
 * partner is asserting completion, rather than this module inferring it. The sentinel's content is
 * ignored except for an optional record count, which - when it is there - is checked against the
 * balance at the end and is the only thing in the module capable of catching a truncation that landed
 * on a record boundary.
 *
 * <h2>Stability, which is the fallback</h2>
 *
 * <p>For a partner who publishes no sentinel: require the size and etag to be identical across two
 * looks, a window apart. Weaker, and honestly so - an upload that stalls for longer than the window
 * looks finished - which is why it is not the default and why the README says what it does not cover.
 */
@Slf4j
public class ArrivalDetector {

    /**
     * How much of a sentinel is read when looking for a declared record count.
     *
     * <p>Generous for a manifest and far below anything that matters to the heap. A sentinel longer
     * than this is not a manifest, and truncating it simply means no expected count is found - which
     * is the same outcome as the overwhelmingly common case of an empty marker.
     */
    private static final int SENTINEL_MAX_BYTES = 64 * 1024;

    private final ObjectStore store;
    private final ObjectMapper objectMapper;

    /**
     * Creates the detector.
     *
     * @param store        where the objects and their sentinels are
     * @param objectMapper for reading a record count out of a JSON sentinel
     */
    public ArrivalDetector(ObjectStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    /**
     * Whether an object may be read, and what its sentinel declared if anything.
     *
     * @param uri      the data object
     * @param metadata its metadata as it was first seen, for the stability comparison
     * @param arrival  the task's arrival configuration
     * @return the arrival verdict
     */
    public Arrival check(ObjectUri uri, StoredObject metadata, FileIngestProperties.Arrival arrival) {
        boolean sentinelConfigured = arrival.getSentinel() != null && !arrival.getSentinel().isBlank();
        if (sentinelConfigured) {
            return checkSentinel(uri, arrival);
        }
        if (arrival.getStabilityWindow().isZero()) {
            // Neither mechanism. Legitimate only for a partner who writes atomically - a rename into
            // place, or a multipart upload that becomes visible complete - and it is a decision the
            // README names rather than a default anyone falls into.
            log.debug("Task has neither a sentinel nor a stability window; {} is read as soon as it is"
                    + " seen", uri.value());
            return Arrival.ready(null);
        }
        return checkStability(uri, metadata, arrival.getStabilityWindow());
    }

    private Arrival checkSentinel(ObjectUri uri, FileIngestProperties.Arrival arrival) {
        ObjectUri sentinel = IngestNaming.sentinelFor(uri, arrival.getSentinel());
        if (!store.exists(sentinel.value())) {
            log.debug("Sentinel {} is absent, so {} is not read", sentinel.value(), uri.value());
            return Arrival.notReady("sentinel " + sentinel.name() + " has not arrived");
        }
        return Arrival.ready(expectedCount(sentinel, arrival.getExpectedCountField()).orElse(null));
    }

    /**
     * Compares the object against itself after the window has passed.
     *
     * <p>Re-reads the metadata rather than trusting what was passed in: the point of the check is that
     * time has elapsed since that reading, and comparing the first reading to itself would pass
     * always.
     */
    private Arrival checkStability(ObjectUri uri, StoredObject first, Duration window) {
        StoredObject second;
        try {
            second = store.head(uri.value());
        } catch (ObjectNotFoundException e) {
            return Arrival.notReady("object disappeared between polls");
        }
        if (second.size() != first.size() || !second.etag().equals(first.etag())) {
            log.debug("{} changed between polls ({} -> {} bytes), so it is still being written",
                    uri.value(), first.size(), second.size());
            return Arrival.notReady("still changing");
        }
        Duration age = Duration.between(second.lastModified(), java.time.Instant.now());
        if (age.compareTo(window) < 0) {
            // Unchanged, but not for long enough. A partner writing at a steady rate into a store
            // whose etag only changes on close would otherwise pass the comparison above on its way
            // up; requiring the window to have elapsed since the last write is what makes the check
            // mean "settled" rather than "was the same twice in a row very quickly".
            return Arrival.notReady("last modified " + age.toSeconds() + "s ago, inside the "
                    + window.toSeconds() + "s stability window");
        }
        return Arrival.ready(null);
    }


    /**
     * Reads a record count out of a JSON sentinel, if it has one.
     *
     * <p>Every failure here is a debug line and an empty result rather than an exception. A sentinel
     * that is empty, or is not JSON, or does not carry the field, is the overwhelmingly common case -
     * most partners write a zero-byte marker - and failing the run over the absence of an optional
     * number would make the strongest check in the module also the most fragile.
     */
    private Optional<Long> expectedCount(ObjectUri sentinel, String field) {
        if (field == null || field.isBlank()) {
            return Optional.empty();
        }
        try (InputStream in = store.open(sentinel.value())) {
            // Bounded, not readAllBytes. A sentinel is a marker - usually zero bytes, at most a small
            // JSON manifest - but it is an object in somebody else's bucket and nothing stops a
            // partner from putting a gigabyte there by mistake. Reading a fixed cap costs nothing and
            // removes the one place in this module where an object's size could reach the heap.
            byte[] content = in.readNBytes(SENTINEL_MAX_BYTES);
            if (content.length == 0) {
                return Optional.empty();
            }
            JsonNode node = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));
            JsonNode value = node.get(field);
            return value != null && value.canConvertToLong() ? Optional.of(value.asLong()) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read an expected record count from {}: {}", sentinel.value(), e.toString());
            return Optional.empty();
        }
    }

    /**
     * Whether an object may be read, and what its sentinel declared.
     *
     * @param ready           whether the object is complete
     * @param reason          why not, when it is not; {@code null} when it is
     * @param expectedRecords the sentinel's declared record count, or {@code null}
     */
    public record Arrival(boolean ready, String reason, Long expectedRecords) {

        /**
         * The object is complete.
         *
         * @param expectedRecords what the sentinel declared, or {@code null}
         * @return the verdict
         */
        public static Arrival ready(Long expectedRecords) {
            return new Arrival(true, null, expectedRecords);
        }

        /**
         * The object is not complete.
         *
         * @param reason why, for the log line
         * @return the verdict
         */
        public static Arrival notReady(String reason) {
            return new Arrival(false, reason, null);
        }
    }
}
