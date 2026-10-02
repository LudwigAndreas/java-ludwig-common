package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ludwigandreas.fileaction.api.FileAction;
import ru.ludwigandreas.fileaction.api.FileActionContext;
import ru.ludwigandreas.fileaction.api.FileScanner;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.api.RowHandler;
import ru.ludwigandreas.fileaction.api.RowOutcome;
import ru.ludwigandreas.fileaction.api.ScanOutcome;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.CorrelationProvider;
import ru.ludwigandreas.fileaction.config.FileActionAutoConfiguration;
import ru.ludwigandreas.fileaction.engine.FileActionRegistry;
import ru.ludwigandreas.fileaction.engine.FileActionSettings;
import ru.ludwigandreas.fileaction.engine.RowReaderFactoryRegistry;
import ru.ludwigandreas.fileaction.format.FormatSniffer;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.fileaction.format.xlsx.write.TemplateWriter;
import ru.ludwigandreas.fileaction.repository.FileActionRowRejectRepository;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;
import ru.ludwigandreas.job.core.lock.RunLock;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.webcore.problem.ProblemMessageBundle;
import ru.ludwigandreas.webcore.problem.ProblemMessages;

/**
 * What the module contributes to a context, and what it refuses to contribute to one.
 *
 * <p>The infrastructure the module needs - repositories, an object store, a transaction manager, a scheduler, the
 * audit sink - is mocked, so that the whole wiring is exercised rather than the half of it that happens to need
 * nothing. What the mocks deliberately do not do is behave: nothing here calls a bean, so a mock that returns null
 * is enough and anything more would be an integration test wearing a disguise.
 *
 * <p>What this covers that nothing else does: the registry being built at startup rather than lazily, so a
 * misconfiguration is a failed context rather than a 500 for the first user; the reader set being complete; and
 * the module staying out of a context that disabled it.
 */
class FileActionAutoConfigurationTest {

    private final ApplicationContextRunner runner = withInfrastructure(new ApplicationContextRunner())
            .withPropertyValues(
                    "ludwig.file-action.storage.uploads=file:///tmp/ludwig-file-action/uploads",
                    "ludwig.file-action.storage.artifacts=file:///tmp/ludwig-file-action/artifacts",
                    "ludwig.file-action.scanning.mode=DISABLED",
                    "ludwig.file-action.actions.order-import.commit-policy=PER_ROW");

    @Test
    @DisplayName("the format-independent beans are contributed")
    void contributesTheFormatBeans() {
        runner.withUserConfiguration(WithHandler.class).run(context -> {
            assertThat(context).hasSingleBean(FormatSniffer.class);
            assertThat(context).hasSingleBean(TemplateWriter.class);
            assertThat(context).hasSingleBean(RowReaderFactoryRegistry.class);
            assertThat(context).hasSingleBean(ProblemMessageBundle.class);
        });
    }

    @Test
    @DisplayName("every format in the closed set has a reader, which is checked when the registry is built")
    void everyFormatHasAReader() {
        runner.withUserConfiguration(WithHandler.class).run(context -> {
            RowReaderFactoryRegistry readers = context.getBean(RowReaderFactoryRegistry.class);
            for (SourceFormat format : SourceFormat.values()) {
                assertThat(readers.require(format)).isNotNull();
            }
        });
    }

    @Test
    @DisplayName("the registry is built at startup, so a misconfiguration is a startup failure")
    void theRegistryIsBuiltAtStartup() {
        runner.withUserConfiguration(WithHandler.class).run(context -> {
            assertThat(context).hasSingleBean(FileActionRegistry.class);
            assertThat(context.getBean(FileActionRegistry.class).find("order-import")).isPresent();
        });
    }

    @Test
    @DisplayName("the settings are resolved out of the properties tree, so the engine takes no configuration")
    void settingsAreResolved() {
        runner.withUserConfiguration(WithHandler.class).run(context -> {
            FileActionSettings settings = context.getBean(FileActionSettings.class);
            assertThat(settings.uploadsPrefix()).isEqualTo("file:///tmp/ludwig-file-action/uploads");
            assertThat(settings.artifactsPrefix()).isEqualTo("file:///tmp/ludwig-file-action/artifacts");
        });
    }

    @Test
    @DisplayName("an action with no handler bean fails the context, rather than the first upload")
    void aConfiguredActionWithNoBeanFailsStartup() {
        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasMessageContaining("no bean is annotated"));
    }

    @Test
    @DisplayName("a handler whose action nobody configured fails the context")
    void aHandlerWithNoConfigurationFailsStartup() {
        withInfrastructure(new ApplicationContextRunner())
                .withPropertyValues(
                        "ludwig.file-action.storage.uploads=file:///tmp/u",
                        "ludwig.file-action.storage.artifacts=file:///tmp/a",
                        "ludwig.file-action.scanning.mode=DISABLED")
                .withUserConfiguration(WithHandler.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("nothing is configured under"));
    }

    @Test
    @DisplayName("scanning REQUIRED with no scanner fails the context, naming the escape")
    void requiredScanningWithNoScannerFailsStartup() {
        runner.withUserConfiguration(WithHandler.class)
                .withPropertyValues("ludwig.file-action.scanning.mode=REQUIRED")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("scanning.mode=DISABLED"));
    }

    @Test
    @DisplayName("scanning REQUIRED with a scanner starts")
    void requiredScanningWithAScannerStarts() {
        runner.withUserConfiguration(WithHandlerAndScanner.class)
                .withPropertyValues("ludwig.file-action.scanning.mode=REQUIRED")
                .run(context -> assertThat(context).hasSingleBean(FileActionRegistry.class));
    }

    @Test
    @DisplayName("a bean annotated @FileAction that is not a handler fails the context")
    void aMisannotatedBeanFailsStartup() {
        runner.withUserConfiguration(WithMisannotatedBean.class)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("do not implement FileActionHandler"));
    }

    @Test
    @DisplayName("the module stays out of a context that disabled it")
    void disabledContributesNothing() {
        runner.withUserConfiguration(WithHandler.class)
                .withPropertyValues("ludwig.file-action.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FileActionRegistry.class));
    }

    /**
     * Supplies the infrastructure beans the module's autoconfiguration requires.
     *
     * <p>Mocks rather than real collaborators, because every assertion in this class is about which beans exist and
     * which refusals happen at startup. None of them calls a bean, so a mock that answers nothing is exactly
     * enough.
     */
    private static ApplicationContextRunner withInfrastructure(ApplicationContextRunner runner) {
        return runner
                .withConfiguration(AutoConfigurations.of(FileActionAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ProblemMessages.class,
                        () -> ProblemMessages.ofBundles("i18n/ludwig-file-action-messages"))
                .withBean(FileActionSubmissionRepository.class,
                        () -> org.mockito.Mockito.mock(FileActionSubmissionRepository.class))
                .withBean(FileActionRowRejectRepository.class,
                        () -> org.mockito.Mockito.mock(FileActionRowRejectRepository.class))
                .withBean(ObjectStore.class, () -> org.mockito.Mockito.mock(ObjectStore.class))
                .withBean(AuditSink.class, () -> org.mockito.Mockito.mock(AuditSink.class))
                .withBean(ActorResolver.class, () -> java.util.Optional::empty)
                .withBean(CorrelationProvider.class, CorrelationProvider::none)
                .withBean(PlatformTransactionManager.class,
                        () -> org.mockito.Mockito.mock(PlatformTransactionManager.class))
                .withBean(TaskScheduler.class, () -> org.mockito.Mockito.mock(TaskScheduler.class))
                .withBean(RunLock.class, () -> org.mockito.Mockito.mock(RunLock.class));
    }

    /** A context with one handler, matching the configured action. */
    @Configuration(proxyBeanMethods = false)
    static class WithHandler {

        /** The handler bean. */
        @Bean
        OrderImportHandler orderImportHandler() {
            return new OrderImportHandler();
        }
    }

    /** A context with a handler and a scanner. */
    @Configuration(proxyBeanMethods = false)
    static class WithHandlerAndScanner extends WithHandler {

        /** The scanner bean. */
        @Bean
        FileScanner scanner() {
            return new FileScanner() {
                @Override
                public ScanOutcome scan(InputStream content, String filename, long sizeBytes) {
                    return ScanOutcome.safe("test");
                }
            };
        }
    }

    /** A context whose annotated bean is not a handler at all. */
    @Configuration(proxyBeanMethods = false)
    static class WithMisannotatedBean {

        /** Not a handler, which the annotation's own type cannot prevent. */
        @Bean
        NotAHandler notAHandler() {
            return new NotAHandler();
        }
    }

    /** The handler the configured action names. */
    @FileAction("order-import")
    static class OrderImportHandler implements RowHandler<OrderLine> {

        @Override
        public RowBinding<OrderLine> binding() {
            return TestActions.BINDING;
        }

        @Override
        public RowOutcome apply(OrderLine row, FileActionContext context) {
            return RowOutcome.applied();
        }
    }

    /** Annotated but not a handler: the case the startup check exists for. */
    @FileAction("order-import")
    static class NotAHandler {
    }
}
