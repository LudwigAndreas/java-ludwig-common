package ru.ludwigandreas.job.core.unit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import ru.ludwigandreas.job.core.schedule.ScheduleSpec;
import ru.ludwigandreas.job.core.schedule.ScheduledJob;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SelfSchedulingLifecycleTest {

    private final ThreadPoolTaskScheduler scheduler = newScheduler();

    private static ThreadPoolTaskScheduler newScheduler() {
        ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
        taskScheduler.setPoolSize(2);
        taskScheduler.setThreadNamePrefix("job-core-test-");
        taskScheduler.initialize();
        return taskScheduler;
    }

    @AfterEach
    void shutdownScheduler() {
        scheduler.shutdown();
    }

    @Test
    void runNowExecutesTheJobBodyOnTheCallingThread() {
        AtomicInteger runs = new AtomicInteger();
        ScheduledJob job = job(Duration.ofHours(1), runs::incrementAndGet);

        assertThat(job.runNow()).isTrue();
        assertThat(runs).hasValue(1);
    }

    /**
     * The guard that stops an actuator-triggered run from doubling a scheduled one. The first caller
     * is parked inside the job body while the second tries to enter.
     */
    @Test
    void asecondConcurrentRunIsRefusedRatherThanRunInParallel() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        ScheduledJob job = job(Duration.ofHours(1), () -> {
            peak.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            inside.countDown();
            awaitQuietly(release);
            concurrent.decrementAndGet();
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> first = executor.submit(job::runNow);
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(job.runNow()).isFalse();

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(peak).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anExceptionFromTheJobBodyIsContainedSoTheScheduleSurvives() {
        AtomicInteger runs = new AtomicInteger();
        ScheduledJob job = job(Duration.ofHours(1), () -> {
            runs.incrementAndGet();
            throw new IllegalStateException("partner unreachable");
        });

        assertThat(job.runNow()).isTrue();
        assertThat(job.runNow()).isTrue();
        assertThat(runs).hasValue(2);
    }

    @Test
    void startSchedulesAndStopCancels() {
        ScheduledJob job = job(Duration.ofHours(1), () -> { });

        assertThat(job.isRunning()).isFalse();
        job.start();
        assertThat(job.isRunning()).isTrue();
        job.stop();
        assertThat(job.isRunning()).isFalse();
    }

    /**
     * Shutdown must not interrupt a run mid-claim. The job body holds for longer than the poll
     * granularity, and {@code stop()} is expected to return only once it has finished.
     */
    @Test
    void stopWaitsForAnInFlightRunInsteadOfAbandoningIt() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        ScheduledJob job = job(Duration.ofHours(1), () -> {
            inside.countDown();
            sleepQuietly(Duration.ofMillis(300));
            completed.incrementAndGet();
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(job::runNow);
            assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();
            job.start();

            job.stop();

            assertThat(completed).hasValue(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void ticksAreRefusedOnceStopHasBeenCalled() {
        AtomicInteger runs = new AtomicInteger();
        ScheduledJob job = job(Duration.ofHours(1), runs::incrementAndGet);

        job.start();
        job.stop();

        assertThat(job.runNow()).isFalse();
        assertThat(runs).hasValue(0);
    }

    @Test
    void jobsRunLateInStartupAndThereforeEarlyInShutdown() {
        assertThat(job(Duration.ofHours(1), () -> { }).getPhase())
                .isEqualTo(SelfSchedulingLifecycle.DEFAULT_PHASE);
    }

    private ScheduledJob job(Duration delay, Runnable work) {
        return new ScheduledJob("test-job", scheduler,
                ScheduleSpec.fixedDelay(delay), Duration.ofSeconds(5), work);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
