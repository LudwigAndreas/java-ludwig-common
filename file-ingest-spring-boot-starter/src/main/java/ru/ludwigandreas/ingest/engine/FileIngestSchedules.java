package ru.ludwigandreas.ingest.engine;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;

/**
 * Owns every per-task schedule and the missing-file monitor, and starts and stops them together.
 *
 * <h2>Why this exists rather than one bean per task</h2>
 *
 * <p>The number of schedules is the number of configured tasks, which is not known until the
 * properties are bound - so they cannot be declared as individual {@code @Bean} methods. The obvious
 * alternative, a {@code @Bean} returning a {@code List} of them, does not work either and fails
 * silently: Spring treats the list as one bean of type {@code List} and never looks inside it, so the
 * {@code SmartLifecycle} contract each element implements is never invoked. Nothing would ever be
 * scheduled and nothing would say so.
 *
 * <p>One lifecycle that owns the others is the arrangement that actually works, and it has a second
 * benefit: shutdown drains them in one place, so a stop that has to wait for three in-flight passes
 * waits for them concurrently rather than one after another.
 */
@Slf4j
public class FileIngestSchedules implements SmartLifecycle {

    private final List<SelfSchedulingLifecycle> schedules;

    private volatile boolean running;

    /**
     * Creates the owner.
     *
     * @param schedules the per-task schedules and the monitor
     */
    public FileIngestSchedules(List<SelfSchedulingLifecycle> schedules) {
        this.schedules = List.copyOf(schedules);
    }

    /**
     * The schedules this owns, for the tests and for the actuator endpoint.
     *
     * @return the schedules
     */
    public List<SelfSchedulingLifecycle> schedules() {
        return schedules;
    }

    @Override
    public void start() {
        schedules.forEach(SelfSchedulingLifecycle::start);
        running = true;
        log.info("File ingest started {} schedule(s)", schedules.size());
    }

    @Override
    public void stop() {
        // Stopped in reverse, so the monitor - which is last in the list - stops first. It is the one
        // component that only reads, so stopping it first costs nothing and keeps the ingest
        // schedules draining for as long as the timeout allows.
        for (int i = schedules.size() - 1; i >= 0; i--) {
            schedules.get(i).stop();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // The same phase the schedules themselves declare: late in startup, early in shutdown,
        // because their last act is a database write recording where a run got to.
        return SelfSchedulingLifecycle.DEFAULT_PHASE;
    }
}
