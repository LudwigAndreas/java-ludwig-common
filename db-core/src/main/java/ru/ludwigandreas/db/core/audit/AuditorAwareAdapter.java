package ru.ludwigandreas.db.core.audit;

import org.springframework.data.domain.AuditorAware;

import java.util.Optional;

public class AuditorAwareAdapter<T> implements AuditorAware<T> {

    private final AuditorProvider<T> auditorProvider;

    public AuditorAwareAdapter(AuditorProvider<T> auditorProvider) {
        this.auditorProvider = auditorProvider;
    }

    @Override
    public Optional<T> getCurrentAuditor() {
        return auditorProvider.getCurrentAuditor();
    }
}
