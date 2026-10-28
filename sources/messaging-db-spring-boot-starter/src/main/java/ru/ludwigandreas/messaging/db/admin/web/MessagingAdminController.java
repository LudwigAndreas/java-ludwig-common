package ru.ludwigandreas.messaging.db.admin.web;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.messaging.db.admin.MessagingAdminOperations;
import ru.ludwigandreas.messaging.db.admin.service.MessagingAdminService;
import ru.ludwigandreas.messaging.db.log.LogStore.ConsumerPosition;
import ru.ludwigandreas.security.admin.AdminAccess;

/**
 * The {@code messaging} administrative area: consumer lag on the database log. Read-only by design.
 */
@RestController
@RequestMapping("/admin/v1/messaging")
public class MessagingAdminController {

    private final MessagingAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the area's operations
     */
    public MessagingAdminController(MessagingAdminService service) {
        this.service = service;
    }

    /**
     * Every consumer group's position, head and lag per partition, without the stored last error.
     *
     * @return the positions
     */
    @GetMapping("/consumers")
    @AdminAccess(MessagingAdminOperations.READ)
    public List<ConsumerPosition> consumers() {
        return service.consumers();
    }
}
