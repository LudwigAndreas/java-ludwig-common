package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import ru.ludwigandreas.fileaction.api.CommitPolicy;
import ru.ludwigandreas.fileaction.api.DocumentHandler;
import ru.ludwigandreas.fileaction.api.ExecutionMode;
import ru.ludwigandreas.fileaction.api.FileActionContext;
import ru.ludwigandreas.fileaction.api.FileActionHandler;
import ru.ludwigandreas.fileaction.api.FileScanner;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.api.RowHandler;
import ru.ludwigandreas.fileaction.api.RowOutcome;
import ru.ludwigandreas.fileaction.api.ScanOutcome;
import ru.ludwigandreas.fileaction.api.ScanningMode;
import ru.ludwigandreas.fileaction.config.FileActionConfigurationValidator;
import ru.ludwigandreas.fileaction.config.FileActionProperties;
import ru.ludwigandreas.fileaction.engine.FileActionRegistry;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;

/**
 * One case per startup refusal.
 *
 * <p>Every one of these is a misconfiguration whose symptom, left alone, is applied business data - and none of
 * them is visible in a test of the deployment, because a test configures what it needs. Startup is the third
 * enforcement point after ArchUnit and Checkstyle, and for a configuration fact it is the only one available.
 */
class FileActionConfigurationValidatorTest {

    @Test
    @DisplayName("a complete configuration resolves")
    void resolvesAValidConfiguration() {
        FileActionRegistry registry = validator(properties(action -> { }), handlers()).validate();

        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.require("order-import").commitPolicy()).isEqualTo(CommitPolicy.PER_ROW);
    }

    @Test
    @DisplayName("an action with no commit policy does not start, and the message names the three values")
    void refusesAMissingCommitPolicy() {
        FileActionProperties properties = properties(action -> action.setCommitPolicy(null));

        assertThatThrownBy(() -> validator(properties, handlers()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit-policy")
                .hasMessageContaining("ALL_OR_NOTHING")
                .hasMessageContaining("PER_ROW");
    }

    @Test
    @DisplayName("a row-level commit policy on a DocumentHandler does not start")
    void refusesARowPolicyOnADocumentHandler() {
        FileActionProperties properties = properties(action ->
                action.setCommitPolicy(CommitPolicy.PER_ROW));

        assertThatThrownBy(() -> validator(properties, Map.of("order-import", new DocumentStub()))
                .validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DocumentHandler")
                .hasMessageContaining("all-or-nothing by construction");
    }

    @Test
    @DisplayName("ALL_OR_NOTHING on a DocumentHandler is accepted, because that is what the shape means")
    void acceptsAllOrNothingOnADocumentHandler() {
        FileActionProperties properties = properties(action ->
                action.setCommitPolicy(CommitPolicy.ALL_OR_NOTHING));

        assertThatCode(() -> validator(properties, Map.of("order-import", new DocumentStub())).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("scanning REQUIRED with no scanner does not start, and the message names the escape")
    void refusesRequiredScanningWithNoScanner() {
        FileActionProperties properties = properties(action -> { });
        properties.getScanning().setMode(ScanningMode.REQUIRED);

        assertThatThrownBy(() -> new FileActionConfigurationValidator(properties, handlers(),
                null, null, true, code -> true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no FileScanner bean")
                .hasMessageContaining("scanning.mode=DISABLED");
    }

    @Test
    @DisplayName("scanning REQUIRED with a scanner starts")
    void acceptsRequiredScanningWithAScanner() {
        FileActionProperties properties = properties(action -> { });
        properties.getScanning().setMode(ScanningMode.REQUIRED);

        assertThatCode(() -> new FileActionConfigurationValidator(properties, handlers(),
                new AlwaysSafe(), null, true, code -> true).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("scanning DISABLED with no scanner starts, because the deployment said so out loud")
    void acceptsDisabledScanning() {
        FileActionProperties properties = properties(action -> { });
        properties.getScanning().setMode(ScanningMode.DISABLED);

        assertThatCode(() -> new FileActionConfigurationValidator(properties, handlers(),
                null, null, true, code -> true).validate())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an INLINE action above the inline ceiling does not start")
    void refusesAnOversizedInlineAction() {
        FileActionProperties properties = properties(action -> {
            action.setExecution(ExecutionMode.INLINE);
            action.setMaxRows(100_000);
        });

        assertThatThrownBy(() -> validator(properties, handlers()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INLINE with max-rows 100000")
                .hasMessageContaining("request timeout");
    }

    @Test
    @DisplayName("the same action is accepted as DEFERRED, which is the message's own advice")
    void acceptsTheSameActionDeferred() {
        FileActionProperties properties = properties(action -> {
            action.setExecution(ExecutionMode.DEFERRED);
            action.setMaxRows(100_000);
        });

        assertThatCode(() -> validator(properties, handlers()).validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an authority with no security starter does not start, rather than serving it unprotected")
    void refusesAnAuthorityWithNoSecurity() {
        FileActionProperties properties = properties(action -> action.setRequiredAuthority("ORDER_IMPORT"));

        assertThatThrownBy(() -> new FileActionConfigurationValidator(properties, handlers(),
                new AlwaysSafe(), null, false, code -> true).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing would check it");
    }

    @Test
    @DisplayName("a configured action with no bean does not start")
    void refusesConfigurationWithNoBean() {
        assertThatThrownBy(() -> validator(properties(action -> { }), Map.of()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no bean is annotated")
                .hasMessageContaining("both must be present");
    }

    @Test
    @DisplayName("a bean with no configuration does not start")
    void refusesABeanWithNoConfiguration() {
        FileActionProperties properties = properties(action -> { });
        properties.getActions().clear();

        assertThatThrownBy(() -> validator(properties, handlers()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nothing is configured under");
    }

    @Test
    @DisplayName("an unset storage prefix does not start, because the only possible default is wrong")
    void refusesMissingStorage() {
        FileActionProperties properties = properties(action -> { });
        properties.getStorage().setUploads(null);

        assertThatThrownBy(() -> validator(properties, handlers()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("storage.uploads")
                .hasMessageContaining("ephemeral disk");
    }

    @Test
    @DisplayName("a reject threshold outside 0..1 does not start")
    void refusesAnImpossibleThreshold() {
        FileActionProperties properties = properties(action -> action.setRejectThreshold(1.5d));

        assertThatThrownBy(() -> validator(properties, handlers()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reject-threshold");
    }

    @Test
    @DisplayName("a message code missing from a locale does not start, naming the codes")
    void refusesAnUnresolvableCode() {
        FileActionProperties properties = properties(action -> { });

        assertThatThrownBy(() -> new FileActionConfigurationValidator(properties, handlers(),
                new AlwaysSafe(), null, true,
                code -> !FileActionProblemCodes.TOO_LARGE.equals(code)).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(FileActionProblemCodes.TOO_LARGE)
                .hasMessageContaining("raw key");
    }

    @Test
    @DisplayName("every problem is reported, not just the first")
    void reportsEveryProblem() {
        FileActionProperties properties = properties(action -> {
            action.setCommitPolicy(null);
            action.setRejectThreshold(2.0d);
        });
        properties.getStorage().setArtifacts(null);

        assertThatThrownBy(() -> validator(properties, handlers()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("commit-policy")
                .hasMessageContaining("reject-threshold")
                .hasMessageContaining("storage.artifacts");
    }

    private static FileActionConfigurationValidator validator(FileActionProperties properties,
                                                              Map<String, FileActionHandler<?>> handlers) {
        return new FileActionConfigurationValidator(properties, handlers, new AlwaysSafe(),
                null, true, code -> true);
    }

    private static Map<String, FileActionHandler<?>> handlers() {
        Map<String, FileActionHandler<?>> handlers = new LinkedHashMap<>();
        handlers.put("order-import", new RowStub());
        return handlers;
    }

    private static FileActionProperties properties(
            java.util.function.Consumer<FileActionProperties.Action> customise) {
        FileActionProperties properties = new FileActionProperties();
        properties.getStorage().setUploads("file:///tmp/ludwig-file-action/uploads");
        properties.getStorage().setArtifacts("file:///tmp/ludwig-file-action/artifacts");
        properties.getScanning().setMode(ScanningMode.OPTIONAL);
        FileActionProperties.Action action = new FileActionProperties.Action();
        action.setCommitPolicy(CommitPolicy.PER_ROW);
        action.setMaxSize(DataSize.ofMegabytes(10));
        action.setMaxRows(2_000);
        customise.accept(action);
        properties.getActions().put("order-import", action);
        return properties;
    }

    /** A row handler, for the cases that are about configuration rather than behaviour. */
    private static final class RowStub implements RowHandler<OrderLine> {
        @Override
        public RowBinding<OrderLine> binding() {
            return TestActions.BINDING;
        }

        @Override
        public RowOutcome apply(OrderLine row, FileActionContext context) {
            return RowOutcome.applied();
        }
    }

    /** A document handler, for the commit-policy cases. */
    private static final class DocumentStub implements DocumentHandler<OrderLine> {
        @Override
        public RowBinding<OrderLine> binding() {
            return TestActions.BINDING;
        }

        @Override
        public RowOutcome apply(Stream<OrderLine> rows, FileActionContext context) {
            return RowOutcome.applied();
        }
    }

    /** A scanner for the cases that need one to be present. */
    private static final class AlwaysSafe implements FileScanner {
        @Override
        public ScanOutcome scan(InputStream content, String filename, long sizeBytes) {
            return ScanOutcome.safe(name());
        }
    }
}
