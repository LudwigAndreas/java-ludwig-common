package ru.ludwigandreas.export.lifecycle;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.export.api.Column;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.api.ReportFormat;
import ru.ludwigandreas.export.entity.ExportSavedReport;
import ru.ludwigandreas.export.exception.SavedReportStaleException;
import ru.ludwigandreas.export.registry.ReportDefinitionRegistry;
import ru.ludwigandreas.export.registry.ReportWriterFactories;
import ru.ludwigandreas.export.repository.ExportReportSubscriptionRepository;
import ru.ludwigandreas.export.repository.ExportSavedReportRepository;

/**
 * Saved configurations, and the check that keeps them honest.
 *
 * <h2>Validated on write and again on load</h2>
 *
 * <p>The second is the one that matters and the one an implementation usually omits. A definition
 * changes when the service is deployed; a configuration written against last month's definition does
 * not change with it. So a saved report naming a column that has since been removed is not a
 * hypothetical - it is what happens the first time somebody deletes a column.
 *
 * <p>The failure is loud and names the column. The alternative - dropping what no longer resolves and
 * producing the rest - yields a file that is quietly narrower than the one the same configuration
 * produced last month, and nobody holding it can tell. That is precisely the class of silent wrongness
 * this module refuses everywhere else, and a saved report is where it is easiest to let in.
 *
 * <h2>Deleting one that something is subscribed to</h2>
 *
 * <p>Refused, naming the subscriptions. The database enforces it too - the foreign key is
 * {@code RESTRICT} - but a constraint violation surfacing as a 500 tells an administrator nothing,
 * so the check is here as well and the message says which schedules depend on it.
 */
@Slf4j
public class SavedReportService {

    private final ExportSavedReportRepository savedReports;
    private final ExportReportSubscriptionRepository subscriptions;
    private final ReportDefinitionRegistry registry;
    private final ReportWriterFactories formats;

    public SavedReportService(ExportSavedReportRepository savedReports,
                              ExportReportSubscriptionRepository subscriptions,
                              ReportDefinitionRegistry registry, ReportWriterFactories formats) {
        this.savedReports = savedReports;
        this.subscriptions = subscriptions;
        this.registry = registry;
        this.formats = formats;
    }

    /** Stores a configuration, having checked it against the definition it names. */
    @Transactional
    public ExportSavedReport save(ExportSavedReport saved) {
        validate(saved);
        if (saved.getId() != null) {
            saved.setRevision(saved.getRevision() + 1);
        }
        return savedReports.save(saved);
    }

    /**
     * Loads a configuration and re-checks it, because the definition may have moved since it was
     * written.
     *
     * @param id the configuration
     * @return the configuration, guaranteed to still describe something the definition can produce
     * @throws SavedReportStaleException naming the first thing that no longer resolves
     */
    @Transactional(readOnly = true)
    public ExportSavedReport require(UUID id) {
        ExportSavedReport saved = savedReports.getByIdOrThrow(id);
        validate(saved);
        return saved;
    }

    /** The configurations a user may pick from, with the stale ones reported rather than hidden. */
    @Transactional(readOnly = true)
    public List<ExportSavedReport> usable() {
        return savedReports.findByEnabledTrueOrderByNameAsc().stream()
                .filter(this::isStillValid)
                .toList();
    }

    /**
     * Removes a configuration, unless something is subscribed to it.
     *
     * @throws SavedReportStaleException naming the subscriptions that depend on it
     */
    @Transactional
    public void delete(UUID id) {
        List<UUID> dependents = subscriptions.findBySavedReportId(id).stream()
                .map(subscription -> subscription.getId())
                .toList();
        if (!dependents.isEmpty()) {
            throw new SavedReportStaleException(id.toString(),
                    "subscriptions " + dependents);
        }
        savedReports.deleteById(id);
    }

    /**
     * Whether this configuration still describes something the definition can produce.
     *
     * <p>Used only by the listing, which hides what it cannot offer rather than failing the whole
     * screen on one broken row. Every other path validates and throws - a user who picked a
     * configuration deserves to be told why it will not run, where a user browsing a list does not
     * need somebody else's broken configuration to stop them seeing their own.
     */
    private boolean isStillValid(ExportSavedReport saved) {
        try {
            validate(saved);
            return true;
        } catch (SavedReportStaleException e) {
            log.warn("Saved report '{}' ({}) no longer matches definition {}: {}", saved.getName(),
                    saved.getId(), saved.getDefinitionKey(), e.getMessage());
            return false;
        }
    }

    private void validate(ExportSavedReport saved) {
        ReportDefinition<?, ?> definition = registry.find(saved.getDefinitionKey())
                .orElseThrow(() -> new SavedReportStaleException(saved.getName(),
                        "report " + saved.getDefinitionKey()));
        checkColumns(saved, definition);
        checkFormat(saved, definition);
        checkFilter(saved, definition);
    }

    private void checkColumns(ExportSavedReport saved, ReportDefinition<?, ?> definition) {
        if (saved.getColumnIds() == null || saved.getColumnIds().isEmpty()) {
            return;
        }
        Set<String> declared = Set.copyOf(definition.getColumns().stream()
                .map(Column::getId)
                .toList());
        for (String columnId : saved.getColumnIds()) {
            if (!declared.contains(columnId)) {
                throw new SavedReportStaleException(saved.getName(), "column " + columnId);
            }
        }
    }

    private void checkFormat(ExportSavedReport saved, ReportDefinition<?, ?> definition) {
        if (saved.getFormatId() == null || saved.getFormatId().isBlank()) {
            return;
        }
        if (!formats.ids().contains(saved.getFormatId())) {
            throw new SavedReportStaleException(saved.getName(), "format " + saved.getFormatId());
        }
        boolean allowed = definition.getAllowedFormats().stream()
                .map(ReportFormat::id)
                .anyMatch(saved.getFormatId()::equals);
        if (!allowed) {
            throw new SavedReportStaleException(saved.getName(), "format " + saved.getFormatId());
        }
    }

    private void checkFilter(ExportSavedReport saved, ReportDefinition<?, ?> definition) {
        boolean hasFilter = saved.getFilterExpression() != null
                && !saved.getFilterExpression().isBlank();
        if (hasFilter && definition.getFilterEntityType() == null) {
            // The definition stopped accepting filters. Running this configuration would either drop
            // the filter - producing a far wider file than was configured - or fail mid-run.
            throw new SavedReportStaleException(saved.getName(), "filtering");
        }
    }
}
