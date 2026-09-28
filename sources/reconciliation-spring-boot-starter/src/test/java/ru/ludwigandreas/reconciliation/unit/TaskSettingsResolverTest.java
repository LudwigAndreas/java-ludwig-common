package ru.ludwigandreas.reconciliation.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.config.AmbiguousSubmitPolicy;
import ru.ludwigandreas.reconciliation.config.FetchShape;
import ru.ludwigandreas.reconciliation.config.NotFoundPolicy;
import ru.ludwigandreas.reconciliation.config.ProcessingMode;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.config.TaskSettingsResolver;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSettingsResolverTest {

    @Test
    void aTaskThatSaysNothingInheritsTheDefaultsBlock() {
        ReconciliationProperties properties = properties();
        properties.getDefaults().getRetry().setMaxAttempts(3);
        properties.getDefaults().setMode(ProcessingMode.DIRECT);
        properties.getDefaults().setNotFound(NotFoundPolicy.MARK_MISSING);

        TaskSettings settings = resolve(properties, task(FetchShape.PER_ITEM));

        assertThat(settings.retry().maxAttempts()).isEqualTo(3);
        assertThat(settings.mode()).isEqualTo(ProcessingMode.DIRECT);
        assertThat(settings.notFound()).isEqualTo(NotFoundPolicy.MARK_MISSING);
    }

    @Test
    void aTaskThatSaysSomethingOverridesTheDefaultsBlock() {
        ReconciliationProperties properties = properties();
        properties.getDefaults().getRetry().setMaxAttempts(3);

        ReconciliationProperties.Task task = task(FetchShape.PER_ITEM);
        task.setRetry(new ReconciliationProperties.Retry());
        task.getRetry().setMaxAttempts(11);

        assertThat(resolve(properties, task).retry().maxAttempts()).isEqualTo(11);
    }

    /**
     * The merge is per field, not per block. A task that sets only max-attempts must keep the default
     * interval and multiplier rather than dropping the whole retry block on the floor.
     */
    @Test
    void mergingIsPerFieldRatherThanPerBlock() {
        ReconciliationProperties properties = properties();
        properties.getDefaults().getRetry().setMaxAttempts(3);
        properties.getDefaults().getRetry().setInitialInterval(Duration.ofSeconds(7));
        properties.getDefaults().getRetry().setMultiplier(3.0);

        ReconciliationProperties.Task task = task(FetchShape.PER_ITEM);
        task.setRetry(new ReconciliationProperties.Retry());
        task.getRetry().setMaxAttempts(11);

        TaskSettings settings = resolve(properties, task);

        assertThat(settings.retry().maxAttempts()).isEqualTo(11);
        assertThat(settings.retry().backoff().initialInterval()).isEqualTo(Duration.ofSeconds(7));
        assertThat(settings.retry().backoff().multiplier()).isEqualTo(3.0);
    }

    @Test
    void whatNobodySetsComesFromTheBuiltInValues() {
        TaskSettings settings = resolve(properties(), task(FetchShape.BATCHED));

        assertThat(settings.mode()).isEqualTo(ProcessingMode.STAGED);
        assertThat(settings.notFound()).isEqualTo(NotFoundPolicy.IGNORE);
        assertThat(settings.fetch().batchSize()).isPositive();
        assertThat(settings.fetch().maxConcurrency()).isPositive();
        assertThat(settings.retry().backoff().jitter()).isPositive();
        assertThat(settings.demand().incremental()).isFalse();
        assertThat(settings.fetch().checkpoint()).isTrue();
    }

    /**
     * A cron expression and a fixed delay are mutually exclusive, so they are inherited as a pair: a
     * task that sets cron must not pick up a default fixed-delay and become contradictory.
     */
    @Test
    void aTaskWithACronDoesNotInheritTheDefaultFixedDelay() {
        ReconciliationProperties properties = properties();
        properties.getDefaults().getSchedule().setFixedDelay(Duration.ofSeconds(30));

        ReconciliationProperties.Task task = task(FetchShape.PAGED);
        task.getSchedule().setCron("0 0 */4 * * *");

        TaskSettings settings = resolve(properties, task);

        assertThat(settings.hotSchedule().isCron()).isTrue();
        assertThat(settings.hotSchedule().cron()).isEqualTo("0 0 */4 * * *");
        assertThat(settings.hotSchedule().fixedDelay()).isNull();
    }

    @Test
    void aTaskWithNoColdScheduleIsNotTiered() {
        assertThat(resolve(properties(), task(FetchShape.PER_ITEM)).isTiered()).isFalse();
    }

    @Test
    void aTaskWithAColdScheduleIsTiered() {
        ReconciliationProperties.Task task = task(FetchShape.PER_ITEM);
        task.setColdSchedule(new ReconciliationProperties.Schedule());
        task.getColdSchedule().setCron("0 0 3 * * *");

        TaskSettings settings = resolve(properties(), task);

        assertThat(settings.isTiered()).isTrue();
        assertThat(settings.coldScheduleOrEmpty()).isPresent();
    }

    @Test
    void aNonAsyncTaskHasNoJobSettings() {
        assertThat(resolve(properties(), task(FetchShape.BATCHED)).jobOrEmpty()).isEmpty();
    }

    @Test
    void anAsyncTaskGetsItsThreeSchedules() {
        ReconciliationProperties.Task task = task(FetchShape.ASYNC_JOB);
        task.setJob(new ReconciliationProperties.Job());
        task.getJob().setOnAmbiguousSubmit(AmbiguousSubmitPolicy.ASSUME_SUBMITTED);
        task.getJob().getPoll().setFixedDelay(Duration.ofSeconds(45));

        TaskSettings.JobSettings job = resolve(properties(), task).jobOrEmpty().orElseThrow();

        assertThat(job.pollSchedule().fixedDelay()).isEqualTo(Duration.ofSeconds(45));
        assertThat(job.submitSchedule().fixedDelay()).isNotNull();
        assertThat(job.collectSchedule().fixedDelay()).isNotNull();
        assertThat(job.onAmbiguousSubmit()).isEqualTo(AmbiguousSubmitPolicy.ASSUME_SUBMITTED);
    }

    /**
     * on-ambiguous-submit is passed through exactly as configured, including when it is absent.
     * Substituting a default here would defeat the setting: the validator has to be able to see that
     * nobody chose.
     */
    @Test
    void anAbsentAmbiguousSubmitPolicyIsNotDefaultedAway() {
        ReconciliationProperties.Task task = task(FetchShape.ASYNC_JOB);
        task.setJob(new ReconciliationProperties.Job());

        assertThat(resolve(properties(), task).jobOrEmpty().orElseThrow().onAmbiguousSubmit()).isNull();
    }

    @Test
    void theRetryBudgetsWorstCaseGrowsWithAttemptsAndIsCappedByMaxInterval() {
        ReconciliationProperties properties = properties();
        properties.getDefaults().getRetry().setMaxAttempts(4);
        properties.getDefaults().getRetry().setInitialInterval(Duration.ofSeconds(1));
        properties.getDefaults().getRetry().setMultiplier(2.0);
        properties.getDefaults().getRetry().setMaxInterval(Duration.ofSeconds(3));

        // 1s + 2s + 3s (capped from 4s) = 6s across the three gaps between four attempts.
        assertThat(resolve(properties, task(FetchShape.PER_ITEM)).retry().worstCaseDuration())
                .isEqualTo(Duration.ofSeconds(6));
    }

    private static ReconciliationProperties properties() {
        return new ReconciliationProperties();
    }

    private static ReconciliationProperties.Task task(FetchShape shape) {
        ReconciliationProperties.Task task = new ReconciliationProperties.Task();
        task.getFetch().setShape(shape);
        return task;
    }

    private static TaskSettings resolve(ReconciliationProperties properties,
                                        ReconciliationProperties.Task task) {
        return TaskSettingsResolver.resolve("billing-status", task, properties);
    }
}
