package ru.ludwigandreas.job.core.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import ru.ludwigandreas.job.core.admin.service.ReplicaCommandViews.CommandView;
import ru.ludwigandreas.job.core.admin.service.ReplicaCommandViews.InstanceView;
import ru.ludwigandreas.job.core.admin.service.ReplicaCommandViews.TargetView;
import ru.ludwigandreas.job.core.control.ControlStore;
import ru.ludwigandreas.job.core.control.ReplicaControl;
import ru.ludwigandreas.security.admin.AdminAccessEvaluator;
import ru.ludwigandreas.security.admin.AdminAudit;
import ru.ludwigandreas.security.admin.AdminGrants;
import ru.ludwigandreas.security.admin.AdminInvocation;
import ru.ludwigandreas.security.admin.AdminOperation;
import ru.ludwigandreas.security.admin.AdminOutcome;
import ru.ludwigandreas.security.admin.AdminReasonRequiredException;
import ru.ludwigandreas.security.admin.AdminTier;
import ru.ludwigandreas.security.principal.LudwigPrincipal;
import ru.ludwigandreas.webcore.operation.OperationFailure;
import ru.ludwigandreas.webcore.operation.OperationResponse;
import ru.ludwigandreas.webcore.operation.OperationResult;
import ru.ludwigandreas.webcore.operation.OperationStatus;

/**
 * The {@code replicas} area: which replicas are live, submitting a control to all of them, and reading what each
 * answered. A submission is authorized by the control's own operation - evaluated after its arguments are bound and
 * validated, because the tier may depend on them - through the same grants and denial path as {@code @AdminAccess}.
 */
public class ReplicaAdminService {

    /** Where commands are served. */
    public static final String COMMANDS = "/admin/v1/replicas/commands/";

    static final String UNACKNOWLEDGED = "ludwig.job.error.control-unacknowledged";

    private static final String RESOURCE = "replica-command";
    private static final int LIVE_INTERVALS = 3;

    private final ControlStore store;
    private final Map<String, ReplicaControl<?>> controls;
    private final String self;
    private final Duration pollInterval;
    private final Duration ackTimeout;
    private final AdminGrants grants;
    private final AdminAccessEvaluator evaluator;
    private final AdminAudit audit;
    private final ObjectMapper json;
    private final Validator validator;

    /**
     * Creates the service.
     *
     * @param store        the control tables
     * @param controls     the controls by name
     * @param self         this replica's owner id, heartbeated before a submission so it is always a target
     * @param pollInterval the replicas' poll interval; live means seen within three
     * @param ackTimeout   how long targets have to acknowledge
     * @param grants       who holds which operation
     * @param evaluator    publishes a denial exactly as {@code @AdminAccess} does
     * @param audit        the administrative audit
     * @param json         binds arguments
     * @param validator    validates arguments, or null when no Jakarta validator is present
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - collaborators and settings the auto-configuration supplies.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ReplicaAdminService(ControlStore store, Map<String, ReplicaControl<?>> controls, String self,
                               Duration pollInterval, Duration ackTimeout, AdminGrants grants,
                               AdminAccessEvaluator evaluator, AdminAudit audit, ObjectMapper json,
                               Validator validator) {
        this.store = store;
        this.controls = Map.copyOf(controls);
        this.self = self;
        this.pollInterval = pollInterval;
        this.ackTimeout = ackTimeout;
        this.grants = grants;
        this.evaluator = evaluator;
        this.audit = audit;
        this.json = json;
        this.validator = validator;
    }

    /**
     * The replicas whose heartbeat is within three poll intervals.
     *
     * @return them, by owner
     */
    public List<InstanceView> replicas() {
        return store.live(pollInterval.multipliedBy(LIVE_INTERVALS)).stream()
                .map(instance -> new InstanceView(instance.owner(), instance.startedAt(), instance.lastSeenAt()))
                .toList();
    }

    /**
     * Submits a control to every replica live now.
     *
     * @param name           the control
     * @param body           its arguments as JSON, or null for none
     * @param principal      the caller
     * @param authentication the caller's authentication, for a published denial
     * @param handler        the handler a denial names
     * @param reasonHeader   the {@code Admin-Reason} header, or null
     * @return the command
     */
    @SuppressWarnings({"unchecked", "rawtypes"}) // the control and its argument type travel together by name
    public CommandView submit(String name, JsonNode body, LudwigPrincipal principal, Authentication authentication,
                              Object handler, String reasonHeader) {
        ReplicaControl control = controls.get(name);
        if (control == null) {
            throw ReplicaAdminException.controlNotFound(name);
        }
        Object arguments = bind(control, body);
        AdminOperation operation;
        try {
            operation = control.operationFor(arguments);
        } catch (IllegalArgumentException e) {
            throw ReplicaAdminException.invalidArguments(name, e.getMessage());
        }
        if (!grants.holds(principal, operation.id())) {
            evaluator.refuse(authentication, handler, "Caller does not hold " + operation.id());
        }
        String reason = reasonHeader == null || reasonHeader.isBlank() ? null : reasonHeader.trim();
        if (reason != null && reason.length() > AdminInvocation.MAX_REASON_LENGTH
                || reason == null && operation.tier() == AdminTier.PRIVILEGED) {
            throw new AdminReasonRequiredException();
        }
        store.heartbeat(self);
        List<String> targets = store.live(pollInterval.multipliedBy(LIVE_INTERVALS)).stream()
                .map(ControlStore.Instance::owner).toList();
        UUID id = UUID.randomUUID();
        String argumentsJson = write(arguments);
        store.insert(id, name, argumentsJson, operation.id(), principal.subject(), ackTimeout, targets);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("control", name);
        facts.put("arguments", json.convertValue(arguments, Map.class));
        facts.put("targets", targets);
        audit.record(new AdminInvocation(operation, principal, reason), RESOURCE, id.toString(), AdminOutcome.SUCCEEDED,
                facts);
        return command(id);
    }

    /**
     * One command with an entry per targeted replica.
     *
     * @param id the command id
     * @return the command
     */
    public CommandView command(UUID id) {
        ControlStore.Command command = store.find(id).orElseThrow(() -> ReplicaAdminException.commandNotFound(id));
        Map<String, ControlStore.Ack> acks = store.acks(id).stream()
                .collect(Collectors.toMap(ControlStore.Ack::owner, ack -> ack));
        List<TargetView> targets = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> silent = new ArrayList<>();
        for (String owner : command.targets()) {
            ControlStore.Ack ack = acks.get(owner);
            if (ack == null) {
                targets.add(new TargetView(owner, command.expired() ? "UNACKNOWLEDGED" : "PENDING", null, null,
                        null));
                silent.add(owner);
            } else {
                targets.add(new TargetView(owner, ack.outcome(), ack.failureCode(), ack.acknowledgedAt(),
                        read(ack.resultJson())));
                if (!"SUCCEEDED".equals(ack.outcome())) {
                    failed.add(owner);
                }
            }
        }
        OperationResponse.OperationResponseBuilder envelope = OperationResponse.builder().id(id.toString())
                .submittedAt(command.submittedAt());
        if (!failed.isEmpty()) {
            envelope.status(OperationStatus.FAILED)
                    .failure(OperationFailure.of("ludwig.job.error.control-failed", failed));
        } else if (silent.isEmpty()) {
            envelope.status(OperationStatus.SUCCEEDED).result(OperationResult.at(COMMANDS + id));
        } else if (command.expired()) {
            envelope.status(OperationStatus.FAILED).failure(OperationFailure.of(UNACKNOWLEDGED, silent));
        } else {
            envelope.status(OperationStatus.RUNNING);
        }
        return new CommandView(id, command.control(), command.operation(), command.submittedBy(),
                command.submittedAt(), command.deadline(), targets, envelope.build());
    }

    @SuppressWarnings("rawtypes")
    private Object bind(ReplicaControl control, JsonNode body) {
        Object arguments;
        try {
            arguments = json.convertValue(body == null ? json.createObjectNode() : body, control.argumentType());
        } catch (IllegalArgumentException e) {
            throw ReplicaAdminException.invalidArguments(control.name(), "the body does not match the arguments");
        }
        if (validator != null) {
            Set<ConstraintViolation<Object>> violations = validator.validate(arguments);
            if (!violations.isEmpty()) {
                throw ReplicaAdminException.invalidArguments(control.name(), violations.stream()
                        .map(violation -> violation.getPropertyPath() + " " + violation.getMessage()).sorted()
                        .collect(Collectors.joining("; ")));
            }
        }
        return arguments;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize control arguments", e);
        }
    }

    private JsonNode read(String value) {
        if (value == null) {
            return null;
        }
        try {
            return json.readTree(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
