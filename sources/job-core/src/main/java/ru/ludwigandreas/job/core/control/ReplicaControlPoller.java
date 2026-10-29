package ru.ludwigandreas.job.core.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.job.core.lock.RunLockHandle;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;

/**
 * This replica's end of the control channel: every poll interval it records its heartbeat, applies each command that
 * targets it and that it has not acknowledged, and acknowledges it. One per replica - deliberately not run-locked,
 * because every replica has to apply every command.
 *
 * <p>A command is applied at most once per process: the applied ids are kept in memory as well as in the
 * acknowledgement row, so a failed acknowledgement does not re-apply on the next pass. A restart can re-apply a command
 * it had not acknowledged, which is why {@link ReplicaControl} requires idempotency.
 */
public class ReplicaControlPoller extends SelfSchedulingLifecycle {

    /** The job's name. */
    public static final String JOB_NAME = "ludwig-replica-control";

    /** The run lock under which one replica purges old commands and stale heartbeats. */
    public static final String PURGE_LOCK = "ludwig-replica-control-purge";

    /** The most a per-replica result may weigh, serialized. */
    public static final int MAX_RESULT_BYTES = 4 * 1024;

    static final String SUCCEEDED = "SUCCEEDED";
    static final String FAILED = "FAILED";
    static final String CODE_FAILED = "ludwig.job.error.control-failed";
    static final String CODE_TIMEOUT = "ludwig.job.error.control-timeout";
    static final String CODE_TOO_LARGE = "ludwig.job.error.control-result-too-large";
    static final String CODE_UNKNOWN = "ludwig.job.error.control-unknown";

    private static final int PURGE_EVERY_PASSES = 30;
    private static final int PURGE_BATCH = 500;
    private static final Duration FORGET_AFTER = Duration.ofDays(1);
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(15);
    private static final Logger log = LoggerFactory.getLogger(ReplicaControlPoller.class);

    private final ControlStore store;
    private final String owner;
    private final Map<String, ReplicaControl<?>> controls;
    private final ObjectMapper json;
    private final ControlSettings settings;
    private final RunLock lock;
    private final Set<UUID> applied = ConcurrentHashMap.newKeySet();
    private final ExecutorService handlers;
    private int passes;

    /**
     * Timings of the channel.
     *
     * @param pollInterval   how often a replica polls
     * @param handlerTimeout how long one control may run
     * @param retention      how long commands are kept
     */
    public record ControlSettings(Duration pollInterval, Duration handlerTimeout, Duration retention) {
    }

    /**
     * Creates the poller.
     *
     * @param scheduler the scheduler it runs on
     * @param store     the control tables
     * @param owner     this replica's owner id
     * @param controls  the controls by name
     * @param json      binds arguments and serializes results
     * @param settings  the channel's timings
     * @param lock      the run lock for the purge
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - collaborators the auto-configuration supplies, each distinct.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReplicaControlPoller(TaskScheduler scheduler, ControlStore store, String owner,
                                Map<String, ReplicaControl<?>> controls, ObjectMapper json, ControlSettings settings,
                                RunLock lock) {
        super(JOB_NAME, scheduler, ScheduleSpec.fixedDelay(settings.pollInterval(), Duration.ZERO), DRAIN_TIMEOUT);
        this.store = store;
        this.owner = owner;
        this.controls = Map.copyOf(controls);
        this.json = json;
        this.settings = settings;
        this.lock = lock;
        this.handlers = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "replica-control-handler");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** This replica's owner id. */
    public String owner() {
        return owner;
    }

    @Override
    public void stop() {
        super.stop();
        handlers.shutdownNow();
    }

    @Override
    protected void runOnce() {
        store.heartbeat(owner);
        for (ControlStore.Command command : store.pendingFor(owner)) {
            if (applied.add(command.id())) {
                Outcome outcome = apply(command);
                store.ack(command.id(), owner, outcome.failureCode() == null ? SUCCEEDED : FAILED,
                        outcome.failureCode(), outcome.resultJson());
            }
        }
        if (++passes % PURGE_EVERY_PASSES == 0) {
            lock.tryAcquire(PURGE_LOCK, settings.pollInterval().multipliedBy(PURGE_EVERY_PASSES))
                    .ifPresent(this::purgeUnder);
        }
    }

    private record Outcome(String failureCode, String resultJson) {
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // the control and its argument type travel together by name
    private Outcome apply(ControlStore.Command command) {
        ReplicaControl control = controls.get(command.control());
        if (control == null) {
            log.warn("Replica control command {} names control '{}', which this replica does not have",
                    command.id(), command.control());
            return new Outcome(CODE_UNKNOWN, null);
        }
        Future<Optional<Object>> run = handlers.submit(() -> {
            Object arguments = json.readValue(command.argumentsJson(), control.argumentType());
            return control.apply(arguments);
        });
        try {
            Optional<Object> result = run.get(settings.handlerTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (result == null || result.isEmpty()) {
                return new Outcome(null, null);
            }
            String serialized = json.writeValueAsString(result.get());
            return serialized.getBytes(StandardCharsets.UTF_8).length > MAX_RESULT_BYTES
                    ? new Outcome(CODE_TOO_LARGE, null) : new Outcome(null, serialized);
        } catch (TimeoutException e) {
            run.cancel(true);
            log.warn("Replica control '{}' did not finish within {} on {}", command.control(),
                    settings.handlerTimeout(), owner);
            return new Outcome(CODE_TIMEOUT, null);
        } catch (ExecutionException e) {
            log.warn("Replica control '{}' failed on {}: {}", command.control(), owner, e.getCause().toString());
            return new Outcome(CODE_FAILED, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(CODE_FAILED, null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return new Outcome(CODE_TOO_LARGE, null);
        }
    }

    private void purgeUnder(RunLockHandle handle) {
        try (RunLockHandle held = handle) {
            int purged = store.purge(settings.retention(), PURGE_BATCH);
            int forgotten = store.forget(FORGET_AFTER);
            if (purged + forgotten > 0) {
                log.info("Replica control purge removed {} command(s) and {} stale replica(s)", purged, forgotten);
            }
        } catch (RuntimeException e) {
            // CHECKSTYLE.OFF: IllegalCatch - a failed purge must not end the poller; the next one tries again.
            log.warn("Replica control purge failed: {}", e.toString());
            // CHECKSTYLE.ON: IllegalCatch
        }
    }
}
