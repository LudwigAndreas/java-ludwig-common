package ru.ludwigandreas.notification.repository;

import java.util.List;
import java.util.Optional;
import ru.ludwigandreas.notification.repository.entity.TemplateRevisionEntity;

/** Template version lookups. */
public interface TemplateRevisionQueryRepository {

    Optional<TemplateRevisionEntity> findByNameAndHash(String templateName, String contentHash);

    /** Highest revision number recorded for this template, or 0 when it has never been seen. */
    int highestRevision(String templateName);

    List<TemplateRevisionEntity> listRevisions(String templateName);
}
