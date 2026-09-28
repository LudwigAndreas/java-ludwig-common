package ru.ludwigandreas.db.core.audit;

import org.springframework.data.domain.AuditorAware;
import ru.ludwigandreas.db.core.metrics.DbCoreMetrics;

import java.util.Optional;

public class AuditorAwareAdapter<T> implements AuditorAware<T> {

    private final AuditorProvider<T> auditorProvider;
    private final DbCoreMetrics metrics;

    public AuditorAwareAdapter(AuditorProvider<T> auditorProvider, DbCoreMetrics metrics) {
        this.auditorProvider = auditorProvider;
        this.metrics = metrics;
    }

    @Override
    public Optional<T> getCurrentAuditor() {
        Optional<T> auditor = auditorProvider.getCurrentAuditor();
        metrics.recordAuditorResolved(auditor.isPresent());
        return auditor;
    }
}
