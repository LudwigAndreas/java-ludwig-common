package ru.ludwigandreas.messaging.db.settings;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code ludwig.messaging.db.*}: which topics are read from the log, and how the log is kept.
 *
 * <p>Validated twice, on purpose: by the constraints below when the application has a validator, and by
 * {@code LogStore}'s constructor always, because the schema name is interpolated into SQL and that check
 * must not depend on what else is on the classpath.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = MessagingDbProperties.PREFIX)
public class MessagingDbProperties {

    /** The property prefix. */
    public static final String PREFIX = "ludwig.messaging.db";

    /** Where writes go, and whether the listed topics are also read from Kafka. */
    public enum Mode {
        /** Every write goes to the log; listed topics are read from the log only. */
        ACTIVE,
        /** Every write goes to Kafka; listed topics are read from the log and Kafka, until the log is drained. */
        DRAINING
    }

    /** Where writes go. */
    @NotNull
    private Mode mode = Mode.ACTIVE;

    /** The topics read from the log. Every other topic is read from Kafka. */
    @NotNull
    private Set<String> consume = new LinkedHashSet<>();

    /**
     * How many partitions each topic is split into. Fixed once records exist: changing it moves keys to
     * other partitions, and per-key ordering across the change is lost.
     */
    @Min(1)
    private int partitions = 8;

    /** How long an idle worker waits before polling the log again. */
    @NotNull
    private Duration pollInterval = Duration.ofSeconds(1);

    /** Records older than this are purged, consumed or not. */
    @NotNull
    private Duration retention = Duration.ofDays(7);

    /** How often the purge runs. */
    @NotNull
    private Duration purgeInterval = Duration.ofHours(1);

    /**
     * The schema the log tables live in, shared by every service on the database. Fixed once deployed: the
     * changesets are checksummed with the name substituted, so changing it fails Liquibase's validation, and
     * the existing log stays in the old schema anyway.
     */
    @NotNull
    @Pattern(regexp = "[a-z_][a-z0-9_]{0,62}")
    private String schema = "ludwig_messaging";
}
