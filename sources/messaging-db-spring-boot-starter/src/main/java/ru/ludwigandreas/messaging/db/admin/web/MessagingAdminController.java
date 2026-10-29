package ru.ludwigandreas.messaging.db.admin.web;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ludwigandreas.messaging.db.admin.MessagingAdminOperations;
import ru.ludwigandreas.messaging.db.admin.service.MessagingAdminService;
import ru.ludwigandreas.messaging.db.admin.service.MessagingAdminService.ConsumerView;
import ru.ludwigandreas.messaging.db.admin.service.MessagingAdminService.MoveResult;
import ru.ludwigandreas.messaging.db.admin.service.MessagingAdminService.PauseState;
import ru.ludwigandreas.security.admin.AdminAccess;

/**
 * The {@code messaging} area: database-log consumer positions, and pausing, resuming and moving a group on a topic.
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
     * Every group's position on every partition, with pause state.
     *
     * @return the positions
     */
    @GetMapping("/consumers")
    @AdminAccess(MessagingAdminOperations.READ)
    public List<ConsumerView> consumers() {
        return service.consumers();
    }

    /**
     * Pauses a group on a topic, on every replica.
     *
     * @param group the consumer group
     * @param topic the topic
     * @return the pair's state
     */
    @PostMapping("/consumers/{group}/topics/{topic}/pause")
    @AdminAccess(MessagingAdminOperations.OPERATE)
    public PauseState pause(@PathVariable String group, @PathVariable String topic) {
        return service.pause(group, topic);
    }

    /**
     * Resumes a group on a topic.
     *
     * @param group the consumer group
     * @param topic the topic
     * @return the pair's state
     */
    @PostMapping("/consumers/{group}/topics/{topic}/resume")
    @AdminAccess(MessagingAdminOperations.OPERATE)
    public PauseState resume(@PathVariable String group, @PathVariable String topic) {
        return service.resume(group, topic);
    }

    /**
     * Moves a paused group's position on a topic.
     *
     * @param group   the consumer group
     * @param topic   the topic
     * @param request where to, and optionally which partition
     * @return each partition's old and new position
     */
    @PostMapping("/consumers/{group}/topics/{topic}/offsets")
    @AdminAccess(MessagingAdminOperations.PRIVILEGED)
    public MoveResult moveOffsets(@PathVariable String group, @PathVariable String topic,
                                  @RequestBody MoveRequest request) {
        return service.moveOffsets(group, topic, request.partition(), request.to(), request.value());
    }

    /**
     * Where to move a group's position.
     *
     * @param partition one partition, or absent for every partition
     * @param to        {@code earliest}, {@code latest}, {@code offset} or {@code timestamp}
     * @param value     the next offset to deliver, or an ISO-8601 instant
     */
    public record MoveRequest(Integer partition, String to, String value) {
    }
}
