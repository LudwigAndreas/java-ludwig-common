package ru.ludwigandreas.reconciliation.unit;

import org.junit.jupiter.api.Test;
import ru.ludwigandreas.reconciliation.api.DemandProvider;
import ru.ludwigandreas.reconciliation.api.DemandRequest;
import ru.ludwigandreas.reconciliation.api.Fetcher;
import ru.ludwigandreas.reconciliation.api.KeyCodec;
import ru.ludwigandreas.reconciliation.api.PageResult;
import ru.ludwigandreas.reconciliation.api.ReconcileResult;
import ru.ludwigandreas.reconciliation.api.Reconciler;
import ru.ludwigandreas.reconciliation.api.ReconciliationTask;
import ru.ludwigandreas.reconciliation.api.SyncTask;
import ru.ludwigandreas.reconciliation.config.AmbiguousSubmitPolicy;
import ru.ludwigandreas.reconciliation.config.FetchShape;
import ru.ludwigandreas.reconciliation.config.ReconciliationConfigurationValidator;
import ru.ludwigandreas.reconciliation.config.ReconciliationProperties;
import ru.ludwigandreas.reconciliation.config.TaskSettings;
import ru.ludwigandreas.reconciliation.config.TaskSettingsResolver;
import ru.ludwigandreas.reconciliation.exception.ReconciliationConfigurationException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ReconciliationConfigurationValidatorTest {

    private static final String TASK = "billing-status";

    @Test
    void aValidConfigurationStarts() {
        ReconciliationProperties properties = withTask(FetchShape.BATCHED);

        assertThatCode(() -> validate(properties, List.of(new BatchedTask(TASK)))).doesNotThrowAnyException();
    }

    @Test
    void aConfiguredTaskWithNoBeanIsRefused() {
        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(withTask(FetchShape.BATCHED), List.of()))
                .withMessageContaining("no @ReconciliationTask bean implements it");
    }

    @Test
    void aBeanWithNoConfigurationIsRefused() {
        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(new ReconciliationProperties(), List.of(new BatchedTask(TASK))))
                .withMessageContaining("has no block under ludwig.reconciliation.tasks");
    }

    @Test
    void aBeanWhoseAnnotationAndNameDisagreeIsRefused() {
        ReconciliationProperties properties = withTask(FetchShape.BATCHED);

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new BatchedTask("something-else"))))
                .withMessageContaining("is annotated @ReconciliationTask");
    }

    @Test
    void aFetcherWhoseShapeContradictsTheConfigurationIsRefused() {
        ReconciliationProperties properties = withTask(FetchShape.PER_ITEM);

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new BatchedTask(TASK))))
                .withMessageContaining("would fetch nothing while reporting successful runs");
    }

    @Test
    void aTaskReferencingAnUnknownQuotaIsRefused() {
        ReconciliationProperties properties = withTask(FetchShape.BATCHED);
        properties.getTasks().get(TASK).setQuota("partner-exports");

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new BatchedTask(TASK))))
                .withMessageContaining("references quota \"partner-exports\", which is not configured");
    }

    @Test
    void aTaskReferencingAnUnknownRateLimitIsRefused() {
        ReconciliationProperties properties = withTask(FetchShape.BATCHED);
        properties.getTasks().get(TASK).setRateLimit("partner-api");

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new BatchedTask(TASK))))
                .withMessageContaining("references rate limit \"partner-api\"");
    }

    @Test
    void aTaskReferencingAnUnknownRestClientIsRefused() {
        ReconciliationProperties properties = withTask(FetchShape.BATCHED);
        properties.getTasks().get(TASK).setRestClient("billing");

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new BatchedTask(TASK)), name -> false))
                .withMessageContaining("names REST client \"billing\"");
    }

    @Test
    void aKnownRestClientIsAccepted() {
        ReconciliationProperties properties = withTask(FetchShape.BATCHED);
        properties.getTasks().get(TASK).setRestClient("billing");

        assertThatCode(() -> validate(properties, List.of(new BatchedTask(TASK)), "billing"::equals))
                .doesNotThrowAnyException();
    }

    @Test
    void anAsyncJobTaskWithNoJobBlockIsRefused() {
        ReconciliationProperties properties = withTask(FetchShape.ASYNC_JOB);

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new JobTask(TASK))))
                .withMessageContaining("has no job block");
    }

    /**
     * The setting with no safe default across partners: guessing either way costs something real, and
     * which cost is acceptable is a property of the integration.
     */
    @Test
    void anAsyncJobTaskWithoutAnAmbiguousSubmitPolicyIsRefused() {
        ReconciliationProperties properties = withTask(FetchShape.ASYNC_JOB);
        properties.getTasks().get(TASK).setJob(new ReconciliationProperties.Job());

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new JobTask(TASK))))
                .withMessageContaining("There is no safe default");
    }

    @Test
    void anAsyncJobTaskWithAPolicyIsAccepted() {
        ReconciliationProperties properties = withTask(FetchShape.ASYNC_JOB);
        properties.getTasks().get(TASK).setJob(new ReconciliationProperties.Job());
        properties.getTasks().get(TASK).getJob().setOnAmbiguousSubmit(AmbiguousSubmitPolicy.ASSUME_SUBMITTED);

        assertThatCode(() -> validate(properties, List.of(new JobTask(TASK)))).doesNotThrowAnyException();
    }

    /**
     * The failure this check prevents is the run lock expiring mid-retry, so a second instance starts
     * the same run against the same partner - the exact thing the lock exists to stop.
     */
    @Test
    void aRunTimeoutShorterThanTheRetryBudgetIsRefused() {
        ReconciliationProperties properties = withTask(FetchShape.BATCHED);
        properties.getDefaults().getRetry().setMaxAttempts(8);
        properties.getDefaults().getRetry().setInitialInterval(Duration.ofMinutes(1));
        properties.getDefaults().getRetry().setMaxInterval(Duration.ofMinutes(10));
        properties.getTasks().get(TASK).getSchedule().setRunTimeout(Duration.ofSeconds(30));

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new BatchedTask(TASK))))
                .withMessageContaining("shorter than its own retry budget");
    }

    @Test
    void aLeaseTtlShorterThanTwiceTheHeartbeatIsRefused() {
        ReconciliationProperties properties = withTask(FetchShape.BATCHED);
        ReconciliationProperties.Quota quota = new ReconciliationProperties.Quota();
        quota.setMaxConcurrent(5);
        quota.setHeartbeatInterval(Duration.ofMinutes(5));
        quota.setLeaseTtl(Duration.ofMinutes(6));
        properties.getQuotas().put("partner-exports", quota);

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new BatchedTask(TASK))))
                .withMessageContaining("shorter than 2x heartbeat-interval");
    }

    @Test
    void aQuotaMaxLifetimeShorterThanItsLeaseTtlIsRefused() {
        ReconciliationProperties properties = withTask(FetchShape.BATCHED);
        ReconciliationProperties.Quota quota = new ReconciliationProperties.Quota();
        quota.setMaxConcurrent(5);
        quota.setHeartbeatInterval(Duration.ofMinutes(1));
        quota.setLeaseTtl(Duration.ofMinutes(15));
        quota.setMaxLifetime(Duration.ofMinutes(5));
        properties.getQuotas().put("partner-exports", quota);

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new BatchedTask(TASK))))
                .withMessageContaining("force-reclaimed before its first renewal");
    }

    @Test
    void twoBeansClaimingOneNameAreRefused() {
        ReconciliationProperties properties = withTask(FetchShape.BATCHED);

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties,
                        List.of(new BatchedTask(TASK), new SecondBatchedTask(TASK))))
                .withMessageContaining("both claim the name");
    }

    /**
     * A disabled task is still validated: a configuration that is wrong while switched off is a
     * configuration that is wrong the moment somebody switches it on, usually during an incident.
     */
    @Test
    void aDisabledTaskIsStillValidated() {
        ReconciliationProperties properties = withTask(FetchShape.PER_ITEM);
        properties.getTasks().get(TASK).setEnabled(false);

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new BatchedTask(TASK))));
    }

    @Test
    void everyProblemIsReportedInOnePassRatherThanOneRestartAtATime() {
        ReconciliationProperties properties = withTask(FetchShape.PER_ITEM);
        properties.getTasks().get(TASK).setQuota("missing-quota");
        properties.getTasks().get(TASK).setRateLimit("missing-limit");

        assertThatExceptionOfType(ReconciliationConfigurationException.class)
                .isThrownBy(() -> validate(properties, List.of(new BatchedTask(TASK))))
                .satisfies(thrown -> org.assertj.core.api.Assertions.assertThat(thrown.getMessage())
                        .contains("missing-quota").contains("missing-limit").contains("fetch nothing"));
    }

    private static ReconciliationProperties withTask(FetchShape shape) {
        ReconciliationProperties properties = new ReconciliationProperties();
        ReconciliationProperties.Task task = new ReconciliationProperties.Task();
        task.getFetch().setShape(shape);
        properties.getTasks().put(TASK, task);
        return properties;
    }

    private static void validate(ReconciliationProperties properties, List<SyncTask<?, ?, ?>> beans) {
        validate(properties, beans, name -> true);
    }

    private static void validate(ReconciliationProperties properties,
                                 List<SyncTask<?, ?, ?>> beans,
                                 Predicate<String> restClients) {
        Map<String, TaskSettings> settings = TaskSettingsResolver.resolveAll(properties);
        new ReconciliationConfigurationValidator(properties, settings, beans, restClients).validate();
    }

    /** A batched task; used both correctly and as the wrong shape for a per-item configuration. */
    @ReconciliationTask(TASK)
    static class BatchedTask implements SyncTask<String, String, String> {

        private final String name;

        BatchedTask(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public DemandProvider<String, String> demand() {
            return new DemandProvider<>() {
                @Override
                public List<String> demand(DemandRequest request) {
                    return List.of();
                }

                @Override
                public Optional<String> byKey(String key) {
                    return Optional.empty();
                }
            };
        }

        @Override
        public Function<String, String> localKey() {
            return Function.identity();
        }

        @Override
        public KeyCodec<String> keyCodec() {
            return KeyCodec.ofString();
        }

        @Override
        public Fetcher<String, String> fetcher() {
            return (Fetcher.Batched<String, String>) keys -> Map.of();
        }

        @Override
        public Reconciler<String, String> reconciler() {
            return (local, external, context) -> ReconcileResult.unchanged();
        }

        @Override
        public Class<String> externalType() {
            return String.class;
        }
    }

    /** A second bean claiming the same name, to prove the duplicate check fires. */
    @ReconciliationTask(TASK)
    static class SecondBatchedTask extends BatchedTask {

        SecondBatchedTask(String name) {
            super(name);
        }
    }

    /** An asynchronous-job task, for the shape-specific checks. */
    @ReconciliationTask(TASK)
    static class JobTask extends BatchedTask {

        JobTask(String name) {
            super(name);
        }

        @Override
        public Fetcher<String, String> fetcher() {
            return new Fetcher.JobFetcher<String, String, String>() {
                @Override
                public String submit(java.util.Collection<String> keys,
                                     ru.ludwigandreas.reconciliation.api.IdempotencyKey idempotencyKey) {
                    return "handle";
                }

                @Override
                public ru.ludwigandreas.reconciliation.api.JobStatus poll(String handle) {
                    return ru.ludwigandreas.reconciliation.api.JobStatus.succeeded();
                }

                @Override
                public PageResult<String> collect(String handle, String cursor) {
                    return PageResult.empty();
                }

                @Override
                public String keyOf(String record) {
                    return record;
                }

                @Override
                public KeyCodec<String> handleCodec() {
                    return KeyCodec.ofString();
                }
            };
        }
    }
}
