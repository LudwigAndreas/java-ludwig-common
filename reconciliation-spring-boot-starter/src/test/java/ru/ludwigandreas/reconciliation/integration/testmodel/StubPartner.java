package ru.ludwigandreas.reconciliation.integration.testmodel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The partner the integration tests talk to, in-process.
 *
 * <p>A stub rather than WireMock for the synchronous shapes, because what these tests assert is the
 * <em>engine's</em> behaviour - how often it calls, which keys it sends, what it does with an absent
 * answer - and that is easier to state against a recording stub than against an HTTP fixture. The HTTP
 * layer a real fetcher uses is the rest-client starter's business and is tested there.
 */
public class StubPartner {

    private final Map<String, BillingRecord> records = new ConcurrentHashMap<>();
    private final Set<String> unknownKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> failingKeys = ConcurrentHashMap.newKeySet();
    private final List<List<String>> calls = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger pageCalls = new AtomicInteger();

    /** Puts a record the partner will return for {@code orderId}. */
    public StubPartner holds(String orderId, String status, Instant changedAt) {
        records.put(orderId, new BillingRecord(orderId, status, changedAt));
        unknownKeys.remove(orderId);
        return this;
    }

    /** Makes the partner claim it has never heard of {@code orderId}. */
    public StubPartner doesNotKnow(String orderId) {
        records.remove(orderId);
        unknownKeys.add(orderId);
        return this;
    }

    /** Makes the partner throw for {@code orderId}. */
    public StubPartner failsOn(String orderId) {
        failingKeys.add(orderId);
        return this;
    }

    /** Stops the partner failing for {@code orderId}. */
    public StubPartner recoversOn(String orderId) {
        failingKeys.remove(orderId);
        return this;
    }

    /** Answers one key. */
    public Optional<BillingRecord> fetchOne(String orderId) {
        calls.add(List.of(orderId));
        raiseIfFailing(List.of(orderId));
        return Optional.ofNullable(records.get(orderId));
    }

    /** Answers a chunk, leaving out keys it does not know - which is a NOT FOUND, not an error. */
    public Map<String, BillingRecord> fetchBatch(java.util.Collection<String> keys) {
        calls.add(List.copyOf(keys));
        raiseIfFailing(keys);
        Map<String, BillingRecord> answer = new LinkedHashMap<>();
        keys.forEach(key -> Optional.ofNullable(records.get(key))
                .ifPresent(record -> answer.put(key, record)));
        return answer;
    }

    /** Everything the partner holds, in insertion order, for the paged shape. */
    public List<BillingRecord> catalogue() {
        return List.copyOf(records.values());
    }

    /** How many page requests have been made, for the resume-from-checkpoint assertions. */
    public int pageCalls() {
        return pageCalls.get();
    }

    /** Records a page request. */
    public void recordPageCall() {
        pageCalls.incrementAndGet();
    }

    /** Every set of keys the partner has been asked about, in order. */
    public List<List<String>> calls() {
        return List.copyOf(calls);
    }

    /** Forgets every recorded call and every injected fault. */
    public void reset() {
        records.clear();
        unknownKeys.clear();
        failingKeys.clear();
        calls.clear();
        pageCalls.set(0);
    }

    private void raiseIfFailing(java.util.Collection<String> keys) {
        keys.stream().filter(failingKeys::contains).findFirst().ifPresent(key -> {
            throw new IllegalStateException("partner is failing on " + key);
        });
    }
}
