package ru.ludwigandreas.idempotency.integration;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.idempotency.api.Idempotent;

/**
 * Handlers to put the filter in front of.
 *
 * <p>The counter is the assertion that actually matters for a replay: a test that checks the response body of
 * a duplicate proves the filter returned <em>something</em>, while a test that checks the handler ran once
 * proves nothing ran twice. Both are asserted, and this is the one that would catch a filter that replayed
 * the body and also executed.
 *
 * <p>The latches are recreated by {@link #reset()} rather than being created once with the controller.
 * A one-shot latch shared by the whole suite makes the in-flight case depend on test order, which is the
 * classic way a concurrency test passes on a developer's machine and fails in CI.
 */
@RestController
@RequestMapping("/api/v1")
public class TestOrderController {

    private final AtomicInteger executions = new AtomicInteger();

    private volatile CountDownLatch release = new CountDownLatch(1);

    private volatile CountDownLatch started = new CountDownLatch(1);

    /** How many times any handler here ran. */
    public int executions() {
        return executions.get();
    }

    /** Fresh latches, so one case cannot decide the next. */
    public void reset() {
        release = new CountDownLatch(1);
        started = new CountDownLatch(1);
    }

    /** Lets a blocked request finish. */
    public void release() {
        release.countDown();
    }

    /**
     * Waits until the blocking endpoint has been entered.
     *
     * @return whether it was entered in time
     * @throws InterruptedException if the test is torn down while waiting
     */
    public boolean awaitStarted() throws InterruptedException {
        return started.await(10, TimeUnit.SECONDS);
    }

    /**
     * Creates an order, once per key.
     *
     * @param body the request
     * @return 201 with a Location header, which is exactly what a replay has to reproduce
     */
    @Idempotent
    @PostMapping("/orders")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        int sequence = executions.incrementAndGet();
        return ResponseEntity.status(201)
                .header("Location", "/api/v1/orders/" + sequence)
                .body(Map.of("sequence", sequence, "amount", body.getOrDefault("amount", 0)));
    }

    /**
     * A second, unrelated operation, so that one key used against two endpoints can be observed.
     *
     * @param body the request
     * @return 201
     */
    @Idempotent
    @PostMapping("/drafts")
    public ResponseEntity<Map<String, Object>> draft(@RequestBody Map<String, Object> body) {
        return ResponseEntity.status(201).body(Map.of("draft", executions.incrementAndGet()));
    }

    /**
     * Creates an order slowly, so that a second call arrives while the first is still running.
     *
     * @param body the request
     * @return 201 once released
     * @throws InterruptedException if the test is torn down while this is parked
     */
    @Idempotent
    @PostMapping("/orders/slow")
    public ResponseEntity<Map<String, Object>> createSlowly(@RequestBody Map<String, Object> body)
            throws InterruptedException {
        executions.incrementAndGet();
        started.countDown();
        release.await(10, TimeUnit.SECONDS);
        return ResponseEntity.status(201).body(Map.of("slow", true, "echo", body));
    }

    /**
     * Always fails.
     *
     * <p>Pins the path that is easy to get wrong in the other direction: a handler that throws must leave the
     * key claimable, or one transient failure becomes a window - a whole lease long - in which the operation
     * cannot be performed at all.
     *
     * @param body the request
     * @return never
     */
    @Idempotent
    @PostMapping("/orders/failing")
    public ResponseEntity<Map<String, Object>> fail(@RequestBody Map<String, Object> body) {
        executions.incrementAndGet();
        throw new IllegalStateException("this handler always fails");
    }
}
