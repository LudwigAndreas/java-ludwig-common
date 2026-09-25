package ru.ludwigandreas.ingest.config;

import jakarta.annotation.PostConstruct;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import ru.ludwigandreas.ingest.api.FileIngest;
import ru.ludwigandreas.ingest.exception.IngestConfigurationException;
import ru.ludwigandreas.storage.api.ObjectUri;
import ru.ludwigandreas.storage.exception.InvalidObjectUriException;

/**
 * Refuses to start on a configuration that is individually valid and jointly wrong.
 *
 * <h2>Why these checks are here and not left to the first run</h2>
 *
 * <p>Every problem below is one that Bean Validation cannot see, because each field is fine on its own
 * and the mistake is in how two of them relate - or in how a field relates to a bean somewhere else in
 * the context. Left unchecked, each of them surfaces at half past six tomorrow morning, in a scheduled
 * job with nobody watching, as a stack trace in a log that will be read the following afternoon.
 * Refusing to start is a worse first five minutes and a very much better first week.
 *
 * <p>They are collected into one message rather than thrown one at a time, because a service being
 * configured for the first time usually has several and discovering them one restart apart is a poor
 * use of an afternoon.
 */
@Slf4j
public class FileIngestConfigurationValidator {

    private final FileIngestProperties properties;
    private final Map<String, FileIngest<?>> beansByName;
    private final boolean outboxAvailable;

    /**
     * Creates the validator.
     *
     * @param properties      the bound configuration
     * @param beansByName     the {@code @IngestTask} beans, by the name each declares
     * @param outboxAvailable whether an outbox publisher is on the context
     */
    public FileIngestConfigurationValidator(FileIngestProperties properties,
                                            Map<String, FileIngest<?>> beansByName,
                                            boolean outboxAvailable) {
        this.properties = properties;
        this.beansByName = beansByName;
        this.outboxAvailable = outboxAvailable;
    }

    /**
     * Runs every check and throws once if any failed.
     *
     * @throws IngestConfigurationException if the configuration is not usable
     */
    @PostConstruct
    public void validate() {
        List<String> problems = new ArrayList<>();
        checkTasksHaveBothHalves(problems);
        properties.getTasks().forEach((name, task) -> checkTask(name, task, problems));
        checkEvents(problems);
        if (!problems.isEmpty()) {
            throw new IngestConfigurationException(problems);
        }
        log.info("File ingest configuration validated: {} task(s)", properties.getTasks().size());
    }

    /**
     * Every configured task has a bean, and every bean has a configured task.
     *
     * <p>Both directions, because they fail differently and both failures are silent. A configured
     * task with no bean is a schedule that fires and finds nothing to run; a bean with no
     * configuration is an ingest somebody wrote, deployed, and that will never be scheduled at all -
     * which is the worse of the two, because nothing about the running service gives any sign of it.
     */
    private void checkTasksHaveBothHalves(List<String> problems) {
        Set<String> configured = new TreeSet<>(properties.getTasks().keySet());
        Set<String> beans = new TreeSet<>(beansByName.keySet());
        configured.stream().filter(name -> !beans.contains(name)).forEach(name ->
                problems.add("task '" + name + "' is configured but no @IngestTask bean declares that"
                        + " name (beans present: " + String.join(", ", beans) + ")"));
        beans.stream().filter(name -> !configured.contains(name)).forEach(name ->
                problems.add("an @IngestTask bean declares '" + name + "' but there is no"
                        + " ludwig.ingest.tasks." + name + " block, so it would never be scheduled"));
    }

    private void checkTask(String name, FileIngestProperties.Task task, List<String> problems) {
        checkSource(name, task, problems);
        checkSchedule(name, task, problems);
        checkBatch(name, task, problems);
        checkArrival(name, task, problems);
        checkAlert(name, task, problems);
    }

    private void checkSource(String name, FileIngestProperties.Task task, List<String> problems) {
        try {
            ObjectUri.parse(task.getSource().getUri());
        } catch (InvalidObjectUriException e) {
            problems.add("task '" + name + "' has source.uri '" + task.getSource().getUri()
                    + "', which is not a storage location: expected s3://bucket/prefix/ or file:///path");
        }
    }

    private void checkSchedule(String name, FileIngestProperties.Task task, List<String> problems) {
        if (!CronExpression.isValidExpression(task.getSchedule().getCron())) {
            problems.add("task '" + name + "' has schedule.cron '" + task.getSchedule().getCron()
                    + "', which Spring cannot parse (six fields, seconds first)");
        }
        if (task.getSchedule().getRunTimeout().compareTo(task.getLock().getLease()) < 0) {
            problems.add("task '" + name + "' has schedule.run-timeout ("
                    + task.getSchedule().getRunTimeout() + ") shorter than lock.lease ("
                    + task.getLock().getLease() + "): the run would be abandoned while it still held"
                    + " a lease nobody else could take");
        }
    }

    private void checkBatch(String name, FileIngestProperties.Task task, List<String> problems) {
        long maxBytes = task.getBatch().getMaxBytes().toBytes();
        long recordLength = task.getQuarantine().getMaxRecordLength().toBytes();
        if (recordLength > maxBytes) {
            problems.add("task '" + name + "' has quarantine.max-record-length (" + recordLength
                    + " bytes) larger than batch.max-bytes (" + maxBytes + " bytes): a quarantine row"
                    + " would be allowed to hold more of a record than a whole batch may hold, which"
                    + " defeats the bound the batch limit exists to enforce");
        }
    }

    private void checkArrival(String name, FileIngestProperties.Task task, List<String> problems) {
        FileIngestProperties.Arrival arrival = task.getArrival();
        boolean sentinel = arrival.getSentinel() != null && !arrival.getSentinel().isBlank();
        if (!sentinel && arrival.getStabilityWindow().isZero()) {
            // A warning rather than a failure: it is legitimate for a partner who writes atomically,
            // and a module that refused to start would be making that call for an estate that may
            // know better. It is loud because getting it wrong produces a SUCCESSFUL run with a
            // truncated tail, which nothing else in the module can detect.
            log.warn("Ingest task {} has neither arrival.sentinel nor arrival.stability-window: the"
                    + " object will be read as soon as it is seen. This is only safe if the partner"
                    + " publishes atomically; otherwise a half-written file produces a successful run"
                    + " with a truncated tail and nothing alerts on it.", name);
        }
        if (sentinel && !arrival.getSentinel().contains("{name}")
                && !arrival.getSentinel().contains("/")) {
            problems.add("task '" + name + "' has arrival.sentinel '" + arrival.getSentinel()
                    + "', which is neither a template containing {name} nor a key: every data object"
                    + " would wait on the same single sentinel");
        }
    }

    private void checkAlert(String name, FileIngestProperties.Task task, List<String> problems) {
        try {
            ZoneId.of(task.getAlert().getZone());
        } catch (DateTimeException e) {
            problems.add("task '" + name + "' has alert.zone '" + task.getAlert().getZone()
                    + "', which is not a known time zone");
        }
        if (task.getAlert().getExpectedBy() == null) {
            // Not a failure - a task nobody wants an alarm for is a real choice - but it is worth
            // saying, because ludwig.ingest.missing is the one metric that catches the failure a
            // once-a-day job actually suffers, and a task without expected-by does not produce it.
            log.info("Ingest task {} has no alert.expected-by, so ludwig.ingest.missing will stay at"
                    + " zero for it: nothing will report a morning on which no file arrives", name);
        }
    }

    private void checkEvents(List<String> problems) {
        if (properties.getEvents().isEnabled() && !outboxAvailable) {
            problems.add("ludwig.ingest.events.enabled is true but no OutboxEventPublisher is on the"
                    + " context: completion events would be silently discarded. Add"
                    + " outbox-spring-boot-starter, or turn the events off");
        }
    }
}
