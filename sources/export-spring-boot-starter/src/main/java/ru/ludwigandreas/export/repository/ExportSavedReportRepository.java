package ru.ludwigandreas.export.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import ru.ludwigandreas.db.core.repository.BaseRepository;
import ru.ludwigandreas.export.entity.ExportSavedReport;

/** Administrator-authored configurations of a definition. */
public interface ExportSavedReportRepository extends BaseRepository<ExportSavedReport, UUID> {

    /** The configurations of one definition, for an admin screen. */
    Page<ExportSavedReport> findByDefinitionKeyOrderByNameAsc(String definitionKey, Pageable pageable);

    /** The configurations a user may pick from. */
    List<ExportSavedReport> findByEnabledTrueOrderByNameAsc();

    /** A configuration by name within a definition, so a name collision is detectable. */
    Optional<ExportSavedReport> findByDefinitionKeyAndName(String definitionKey, String name);
}
