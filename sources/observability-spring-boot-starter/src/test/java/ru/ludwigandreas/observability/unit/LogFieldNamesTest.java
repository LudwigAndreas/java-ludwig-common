package ru.ludwigandreas.observability.unit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.ludwigandreas.observability.config.ObservabilityProperties.Logging.FieldSet;
import ru.ludwigandreas.observability.logging.json.LogFieldNames;

/** Every field set names the commit field, each in its own convention. */
class LogFieldNamesTest {

    @ParameterizedTest
    @EnumSource(FieldSet.class)
    void namesTheCommitFieldInEveryFieldSet(FieldSet fieldSet) {
        // The point of offering more than one set is that an aggregator needs no ingest-time
        // mapping. A field one set leaves unnamed is a field that set silently never writes.
        assertThat(LogFieldNames.of(fieldSet).commitId()).isNotBlank();
    }

    @Test
    void followsTheDottedConventionForEcs() {
        assertThat(LogFieldNames.ecs().commitId()).isEqualTo("service.commit.id");
    }

    @Test
    void followsThePascalCaseConventionForOtel() {
        assertThat(LogFieldNames.otel().commitId()).isEqualTo("ServiceCommitId");
    }

    @Test
    void followsTheSnakeCaseConventionForFlat() {
        assertThat(LogFieldNames.flat().commitId()).isEqualTo("commit_id");
    }

    @Test
    void doesNotCollideWithAFieldTheSameSetAlreadyUses() {
        for (FieldSet fieldSet : FieldSet.values()) {
            LogFieldNames names = LogFieldNames.of(fieldSet);
            assertThat(names.commitId())
                    .isNotIn(names.serviceName(), names.serviceNamespace(), names.serviceVersion(),
                            names.serviceEnvironment(), names.serviceInstance(), names.traceId(), names.spanId(),
                            names.correlationId());
        }
    }

    @Test
    void keepsTheNineteenNameConstructorWorkingWithNoCommitField() {
        // The signature the record had before the commit field joined it. A custom field set built
        // this way must keep compiling, and simply does not get the new field.
        LogFieldNames custom = new LogFieldNames(
                "ts", "lvl", null, "logger", "thread", "msg", "template", "trace", "span", "corr",
                "svc", "ns", "ver", "env", "inst", "err_type", "err_msg", "stack", "markers");

        assertThat(custom.commitId()).isNull();
        assertThat(custom.markers()).isEqualTo("markers");
    }
}
