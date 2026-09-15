package ru.ludwigandreas.identity.repository;

import java.util.List;
import ru.ludwigandreas.identity.entity.SecurityGrantEntity;
import ru.ludwigandreas.security.principal.PrincipalType;

public interface SecurityGrantQueryRepository {

    /**
     * Every unexpired grant this principal holds for one resource and action.
     *
     * <p>Narrowed to resource and action in the query rather than loading a principal's grants once and
     * filtering in memory: the filtered version is cacheable per principal and looks tempting, but it
     * makes the cache entry grow with the number of resources and puts grants for resources the caller
     * never touches into memory on every request.
     */
    List<SecurityGrantEntity> activeGrants(String subject, PrincipalType principalType,
                                           String resourceType, String action);
}
