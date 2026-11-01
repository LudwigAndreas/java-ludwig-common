package ru.ludwigandreas.hotreload.admin.web;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.hotreload.admin.HotReloadAdminOperations;
import ru.ludwigandreas.hotreload.admin.service.HotReloadAdminService;
import ru.ludwigandreas.hotreload.admin.service.HotReloadAdminService.SourceView;
import ru.ludwigandreas.security.operation.RequiresOperation;

/**
 * The {@code hotreload} area's listing. Reloading a source is the {@code hotreloadreload} replica control, submitted
 * through {@code POST /admin/v1/replicas/controls/hotreloadreload}, because it has to reach every replica.
 */
@RestController
@RequestMapping("/admin/v1/hotreload")
public class HotReloadAdminController {

    private final HotReloadAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the listing
     */
    public HotReloadAdminController(HotReloadAdminService service) {
        this.service = service;
    }

    /**
     * The reloadable sources with their kind and last reload on this replica, never a value.
     *
     * @return them
     */
    @GetMapping("/sources")
    @RequiresOperation(HotReloadAdminOperations.SOURCE_READ)
    public List<SourceView> sources() {
        return service.sources();
    }
}
