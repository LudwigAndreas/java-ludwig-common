package ru.ludwigandreas.reconciliation.integration.testmodel;

import ru.ludwigandreas.reconciliation.api.FeedRequest;
import ru.ludwigandreas.reconciliation.api.PageResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A partner that lists collections, in memory.
 *
 * <p>Pages by offset, which is the least forgiving paging there is and so the one worth testing
 * against. Records every call so a test can assert not only what was applied but which pages were
 * asked for, with which bound.
 */
public class StubCollectionPartner {

    /** One call to the partner. */
    public record Call(String collection, String cursor, Instant changedSince) {
    }

    private final Map<String, List<PartnerItem>> collections = new ConcurrentHashMap<>();
    private final Map<String, Integer> failingOffsets = new ConcurrentHashMap<>();
    private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

    /** Forgets everything. */
    public void reset() {
        collections.clear();
        failingOffsets.clear();
        calls.clear();
    }

    /**
     * Replaces what the partner lists under a collection.
     *
     * @param collection the collection
     * @param items      its items, in listing order
     * @return this
     */
    public StubCollectionPartner lists(String collection, PartnerItem... items) {
        collections.put(collection, List.of(items));
        return this;
    }

    /**
     * Makes the page starting at an offset fail until told otherwise.
     *
     * @param collection the collection
     * @param offset     the offset of the page to fail on; negative to stop failing
     */
    public void failsAt(String collection, int offset) {
        if (offset < 0) {
            failingOffsets.remove(collection);
        } else {
            failingOffsets.put(collection, offset);
        }
    }

    /** Every call made since the last reset. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** Forgets the calls made so far, keeping the data. */
    public void forgetCalls() {
        calls.clear();
    }

    /**
     * Lists one page of a collection.
     *
     * @param collection the collection
     * @param cursor     the offset as text, or null for the first page
     * @param request    page size and the changed-since bound
     * @return the page
     */
    public PageResult<PartnerItem> page(String collection, String cursor, FeedRequest request) {
        calls.add(new Call(collection, cursor, request.changedSince()));
        int offset = cursor == null ? 0 : Integer.parseInt(cursor);
        Integer failing = failingOffsets.get(collection);
        if (failing != null && failing == offset) {
            throw new IllegalStateException("stub partner is down at offset " + offset);
        }
        List<PartnerItem> listed = collections.getOrDefault(collection, List.of()).stream()
                .filter(item -> request.changedSince() == null
                        || item.changedAt() != null && item.changedAt().isAfter(request.changedSince()))
                .toList();
        int end = Math.min(listed.size(), offset + request.pageSize());
        List<PartnerItem> items = offset >= listed.size() ? List.of() : listed.subList(offset, end);
        return end < listed.size() ? PageResult.of(items, String.valueOf(end)) : PageResult.last(items);
    }
}
