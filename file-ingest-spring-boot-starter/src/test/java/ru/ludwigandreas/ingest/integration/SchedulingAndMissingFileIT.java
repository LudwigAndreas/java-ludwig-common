package ru.ludwigandreas.ingest.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import ru.ludwigandreas.ingest.engine.FileIngestSchedules;
import ru.ludwigandreas.ingest.engine.IngestPass;
import ru.ludwigandreas.ingest.engine.MissingFileMonitor;
import ru.ludwigandreas.job.core.schedule.SelfSchedulingLifecycle;

/**
 * The schedules are actually created and started, and the missing-file gauge actually moves.
 *
 * <h2>Why the wiring needs its own test</h2>
 *
 * <p>Every other integration test in this suite sets {@code scheduler-enabled=false} and drives a pass
 * explicitly, because waiting for a cron entry would be slow and would pass for the wrong reason on a
 * slow machine. That leaves the scheduling path itself unexercised - and it is a path where a
 * plausible-looking mistake is completely silent. A {@code @Bean} returning a bare {@code List} of
 * {@code SmartLifecycle}s, which is the obvious way to create one schedule per configured task, is one
 * bean Spring never looks inside: nothing is ever scheduled and nothing says so. That was written
 * first; this test is what would have caught it.
 *
 * <h2>And why the gauge does</h2>
 *
 * <p>{@code ludwig.ingest.missing} is the one signal worth alerting on, because for a once-a-day job
 * the failure nobody notices is the file that never came. A gauge that is always zero looks exactly
 * like a healthy morning, so "it is registered" is not the assertion worth making - "it goes to one
 * when the deadline has passed with nothing ingested" is.
 */
@ContextConfiguration(classes = SchedulingAndMissingFileIT.FixedClockConfiguration.class)
@TestPropertySource(properties = {
        // The one test in the suite that leaves the scheduler on.
        "ludwig.ingest.scheduler-enabled=true",
        // Far in the future, so the cron never actually fires during the test: the assertion is that
        // the schedule exists and is running, not that it ran.
        "ludwig.ingest.tasks.partner-catalogue.schedule.cron=0 0 4 1 1 *",
        "ludwig.ingest.tasks.partner-catalogue.alert.expected-by=07:00",
        "ludwig.ingest.tasks.partner-catalogue.alert.zone=UTC"
})
class SchedulingAndMissingFileIT extends FileIngestTestBase {

    @Autowired
    private FileIngestSchedules schedules;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private IngestPass pass;

    @Test
    @DisplayName("one schedule per enabled task plus the monitor, and all of them running")
    void theSchedulesAreCreatedAndStarted() {
        assertThat(schedules.schedules())
                .as("one schedule for partner-catalogue plus the missing-file monitor")
                .hasSize(2);
        assertThat(schedules.schedules())
                .extracting(SelfSchedulingLifecycle::jobName)
                .containsExactly("ludwig-file-ingest-partner-catalogue", MissingFileMonitor.JOB_NAME);
        assertThat(schedules.isRunning()).isTrue();
        assertThat(schedules.schedules()).allSatisfy(schedule ->
                assertThat(schedule.isRunning())
                        .as("%s must be running: a schedule Spring never started is a task that never"
                                + " runs, silently", schedule.jobName())
                        .isTrue());
    }

    @Test
    @DisplayName("the missing gauge goes to one past the deadline with nothing ingested, and back to zero")
    void theMissingGaugeReportsTheFileThatNeverCame() {
        // The clock is fixed at 09:00 UTC, past the 07:00 deadline, and nothing has been ingested.
        runTheMonitorOnce();

        assertThat(gauge())
                .as("past expected-by with no completed run, ludwig.ingest.missing must be non-zero -"
                        + " this is the only signal that distinguishes a quiet morning from a broken"
                        + " one, because the run count and the error count both stay where they were")
                .isEqualTo(1.0);

        // Now ingest something, and the gauge must clear.
        put(dropKey("catalogue-missing.csv"), "SKU-1,widget,100\n");
        pass.runOnce(CatalogueIngest.TASK);
        runTheMonitorOnce();

        assertThat(gauge()).isZero();
    }

    private void runTheMonitorOnce() {
        schedules.schedules().stream()
                .filter(MissingFileMonitor.class::isInstance)
                .forEach(SelfSchedulingLifecycle::runNow);
    }

    private double gauge() {
        return meterRegistry.get("ludwig.ingest.missing")
                .tag("task", CatalogueIngest.TASK)
                .gauge()
                .value();
    }

    /** Fixes the clock past the task's expected-by time, and supplies a registry to read. */
    @TestConfiguration
    static class FixedClockConfiguration {

        /**
         * 09:00 UTC, two hours past the configured deadline.
         *
         * <p>A fixed clock rather than waiting: the alternative is a test whose meaning depends on what
         * time of day it runs, which for a check *about* a time of day is the one property it must not
         * have.
         *
         * @return the clock
         */
        @Bean
        @Primary
        Clock fileIngestClock() {
            return Clock.fixed(Instant.parse("2026-09-25T09:00:00Z"), ZoneOffset.UTC);
        }

        /**
         * A registry the test can read, since the module's metrics are only wired when one is present.
         *
         * @return a simple registry
         */
        @Bean
        @Primary
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
