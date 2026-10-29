package ru.ludwigandreas.job.core.admin.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.job.core.admin.ReplicaAdminOperations;
import ru.ludwigandreas.job.core.admin.service.ReplicaAdminService;
import ru.ludwigandreas.job.core.admin.service.ReplicaCommandViews.CommandView;
import ru.ludwigandreas.job.core.admin.service.ReplicaCommandViews.InstanceView;
import ru.ludwigandreas.security.admin.AdminAccess;
import ru.ludwigandreas.security.admin.AdminAuthenticated;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.principal.SecurityPrincipals;
import ru.ludwigandreas.webcore.operation.OperationLocationHeader;
import ru.ludwigandreas.webcore.operation.OperationResponses;

/**
 * The {@code replicas} area: live replicas, controls broadcast to all of them, and what each replica answered.
 */
@RestController
@RequestMapping("/admin/v1/replicas")
public class ReplicaAdminController {

    /** How often a client should poll a command that is still running. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    private final ReplicaAdminService service;

    /**
     * Creates the controller.
     *
     * @param service the area's operations
     */
    public ReplicaAdminController(ReplicaAdminService service) {
        this.service = service;
    }

    /**
     * The live replicas.
     *
     * @return them
     */
    @GetMapping
    @AdminAccess(ReplicaAdminOperations.READ)
    public List<InstanceView> replicas() {
        return service.replicas();
    }

    /**
     * Submits a control to every live replica, authorized by the operation the control declares for its arguments.
     *
     * @param control the control
     * @param body    its arguments
     * @param reason  the stated reason, required when the control's operation is privileged
     * @return {@code 202} with the command and {@code Location} naming it
     */
    @PostMapping("/controls/{control}")
    @AdminAuthenticated
    public ResponseEntity<CommandView> submit(@PathVariable String control,
                                              @RequestBody(required = false) JsonNode body,
                                              @RequestHeader(name = AdminInvocation.REASON_HEADER, required = false)
                                              String reason) {
        CommandView command = service.submit(control, body, SecurityPrincipals.require(),
                SecurityContextHolder.getContext().getAuthentication(), this, reason);
        return OperationResponses.accepted(command.operationEnvelope(), command,
                URI.create(ReplicaAdminService.COMMANDS + command.id()), OperationLocationHeader.LOCATION);
    }

    /**
     * One command, with an entry per targeted replica.
     *
     * @param id the command id
     * @return the command; a running one carries {@code Retry-After}
     */
    @GetMapping("/commands/{id}")
    @AdminAccess(ReplicaAdminOperations.READ)
    public ResponseEntity<CommandView> command(@PathVariable UUID id) {
        CommandView command = service.command(id);
        return OperationResponses.poll(command.operationEnvelope(), command, POLL_INTERVAL);
    }
}
