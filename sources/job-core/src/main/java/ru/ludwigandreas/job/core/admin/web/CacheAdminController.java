package ru.ludwigandreas.job.core.admin.web;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.job.core.admin.CacheAdminOperations;
import ru.ludwigandreas.job.core.admin.service.CacheAdminService;
import ru.ludwigandreas.job.core.admin.service.CacheAdminService.CacheView;
import ru.ludwigandreas.security.admin.AdminAccess;

/**
 * The {@code caches} area's listing. Clearing a cache is the {@code cacheevict} replica control, submitted through
 * {@code POST /admin/v1/replicas/controls/cacheevict}, because it has to reach every replica.
 */
@RestController
@RequestMapping("/admin/v1/caches")
public class CacheAdminController {

    private final CacheAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the listing
     */
    public CacheAdminController(CacheAdminService service) {
        this.service = service;
    }

    /**
     * The registered caches with purpose, tiers and TTL.
     *
     * @return them
     */
    @GetMapping
    @AdminAccess(CacheAdminOperations.READ)
    public List<CacheView> caches() {
        return service.caches();
    }
}
