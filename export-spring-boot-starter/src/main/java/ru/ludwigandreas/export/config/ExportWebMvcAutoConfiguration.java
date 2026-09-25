package ru.ludwigandreas.export.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.DispatcherServlet;
import ru.ludwigandreas.export.api.ReportSink;
import ru.ludwigandreas.export.i18n.ExportMessages;
import ru.ludwigandreas.export.lifecycle.ExportRunService;
import ru.ludwigandreas.export.lifecycle.ReportRequestService;
import ru.ludwigandreas.export.registry.ReportDefinitionRegistry;
import ru.ludwigandreas.export.registry.ReportWriterFactories;
import ru.ludwigandreas.export.repository.ExportReportOutputRepository;
import ru.ludwigandreas.export.repository.ExportReportRunRepository;
import ru.ludwigandreas.export.security.ReportAuthorities;
import ru.ludwigandreas.export.web.ReportCaller;
import ru.ludwigandreas.export.web.ReportRunController;
import ru.ludwigandreas.export.web.SecurityReportCaller;

/**
 * The REST layer, when there is a servlet stack and the module was not told to stay quiet.
 *
 * <h2>Why this is a separate autoconfiguration and not a member class</h2>
 *
 * <p>It used to be a {@code @Configuration} member of {@link ExportWebAutoConfiguration}, and the
 * controller was silently absent - every report request answered 404 while the startup log reported a
 * validated configuration with one definition, which is about the least diagnosable failure this
 * module could have.
 *
 * <p>The cause is Spring's processing order: member classes of a configuration class are registered
 * <em>before</em> that class's own {@code @Bean} methods, so a {@code @ConditionalOnBean} inside a
 * member class is evaluated while the enclosing class's beans do not yet exist. The controller is
 * conditional on {@link ReportRequestService}, which the enclosing class defines, so the condition
 * could never hold no matter how the module was configured.
 *
 * <p>The general rule, and the one worth carrying to the next module: a member class is the right tool
 * for {@code @ConditionalOnClass} - which is answered from the classpath and needs no beans - and the
 * wrong one for {@code @ConditionalOnBean} on anything the enclosing class produces. That wants a
 * separate autoconfiguration ordered {@code after} it, which is what this is.
 */
@AutoConfiguration(after = ExportWebAutoConfiguration.class)
@ConditionalOnClass(DispatcherServlet.class)
// Both switches in one annotation because @ConditionalOnProperty is not repeatable, and both must
// hold: a module switched off wholesale, and a module whose REST layer specifically is switched off
// because the service drives reports from a scheduler and exposes no endpoints.
@ConditionalOnProperty(prefix = "ludwig.export", name = {"enabled", "web.enabled"},
        matchIfMissing = true)
public class ExportWebMvcAutoConfiguration {

    /**
     * Who is asking, read from the security context.
     *
     * @return the caller resolver
     */
    @Bean
    @ConditionalOnMissingBean
    public ReportCaller reportCaller() {
        return new SecurityReportCaller();
    }

    /**
     * The five endpoints.
     *
     * <p>Conditional on {@link ReportRequestService}, which exists only when the module has both a
     * planner and a persistence layer - so a service that declared definitions but configured no
     * datasource gets no endpoints rather than endpoints that fail on the first call.
     *
     * @param requests    submits and runs
     * @param lifecycle   records outcomes and cancellation
     * @param runs        run rows, for status and the visibility check
     * @param outputs     produced files
     * @param registry    definitions, for the listing endpoint
     * @param formats     the writer factories, for the per-format capability check
     * @param authorities the requester's authorities, re-resolved
     * @param sink        opens a stored file for download
     * @param messages    localized text for the listing and the file name
     * @param caller      who is asking
     * @return the controller
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a @Bean method whose arguments are all named beans.
    @SuppressWarnings("checkstyle:ParameterNumber")
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ReportRequestService.class)
    public ReportRunController reportRunController(ReportRequestService requests,
                                                   ExportRunService lifecycle,
                                                   ExportReportRunRepository runs,
                                                   ExportReportOutputRepository outputs,
                                                   ReportDefinitionRegistry registry,
                                                   ReportWriterFactories formats,
                                                   ReportAuthorities authorities, ReportSink sink,
                                                   ExportMessages messages, ReportCaller caller) {
        return new ReportRunController(requests, lifecycle, runs, outputs, registry, formats,
                authorities, sink, messages, caller);
    }
}
