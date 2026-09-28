package ru.ludwigandreas.db.core.audit;

import java.util.Optional;

@FunctionalInterface
public interface AuditorProvider<T> {

    Optional<T> getCurrentAuditor();
}
