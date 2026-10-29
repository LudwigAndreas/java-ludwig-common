package ru.ludwigandreas.job.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the scheduling primitives shared by the job-shaped modules in this platform.
 *
 * <p>Deliberately tiny. Everything that varies per job - schedules, batch sizes, retry budgets - is
 * configured by the module that owns the job, under that module's own prefix. What lives here is only
 * what is genuinely process-wide: who this instance says it is, and whether the shared schema is
 * applied.
 */
@ConfigurationProperties(prefix = "ludwig.job-core")
public class JobCoreProperties {

    /** Master switch for this module's autoconfiguration. */
    private boolean enabled = true;

    /**
     * Identity written into lock-owner columns. Defaults to {@code <hostname>-<random>}; set it when
     * the deployment already has a meaningful instance name (a pod name, a StatefulSet ordinal).
     */
    private String owner;

    private final Lock lock = new Lock();

    private final Liquibase liquibase = new Liquibase();

    private final Control control = new Control();

    /** Whether this module's autoconfiguration is active. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Sets whether this module's autoconfiguration is active. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** The configured instance identity, or null to derive one. */
    public String getOwner() {
        return owner;
    }

    /** Sets the instance identity written into lock-owner columns. */
    public void setOwner(String owner) {
        this.owner = owner;
    }

    /** Run-lock settings. */
    public Lock getLock() {
        return lock;
    }

    /** The replica-control channel. */
    public Control getControl() {
        return control;
    }

    /** Schema-management settings. */
    public Liquibase getLiquibase() {
        return liquibase;
    }

    /**
     * Settings for the shared {@code RunLock}.
     *
     * <p>Process-wide by the same test as everything else here: the lease length is the deployment's
     * failover time for <em>every</em> job that does not state its own, so it is a property of the
     * deployment rather than of any one job.
     */
    public static class Lock {

        /**
         * Lease taken by the no-TTL {@code runIfAvailable} overload.
         *
         * <p>This is the failover time a job inherits by not choosing one: a pod that dies holding
         * the lock blocks that job for exactly this long. Five minutes is long enough that a
         * garbage-collection pause or a slow statement cannot cost a running job its lease, and short
         * enough that a killed pod costs one missed window rather than an afternoon. A job whose runs
         * are longer or shorter than that passes its own TTL to the explicit overload rather than
         * moving this number for everyone.
         */
        private Duration defaultLease = Duration.ofMinutes(5);

        /** The lease length used when a caller names none. */
        public Duration getDefaultLease() {
            return defaultLease;
        }

        /** Sets the lease length used when a caller names none. */
        public void setDefaultLease(Duration defaultLease) {
            this.defaultLease = defaultLease;
        }
    }

    /** Whether the shipped changelog is applied by this module. */
    public static class Liquibase {

        private boolean enabled = true;

        /** Whether the module applies its own changelog. */
        public boolean isEnabled() {
            return enabled;
        }

        /** Set false when the application folds this module's changelog into its own master changelog. */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** The replica-control channel: how controls reach every replica through this module's tables. */
    public static class Control {

        /** Whether this replica heartbeats and applies controls. */
        private boolean enabled = true;

        /** How often a replica polls for commands and records its heartbeat; live means seen within three. */
        private Duration pollInterval = Duration.ofSeconds(2);

        /** How long the targets of a command have to acknowledge it. */
        private Duration ackTimeout = Duration.ofSeconds(30);

        /** How long one control may run on a replica before it is acknowledged as timed out. */
        private Duration handlerTimeout = Duration.ofSeconds(10);

        /** How long commands and their acknowledgements are kept. */
        private Duration retention = Duration.ofDays(7);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = pollInterval;
        }

        public Duration getAckTimeout() {
            return ackTimeout;
        }

        public void setAckTimeout(Duration ackTimeout) {
            this.ackTimeout = ackTimeout;
        }

        public Duration getHandlerTimeout() {
            return handlerTimeout;
        }

        public void setHandlerTimeout(Duration handlerTimeout) {
            this.handlerTimeout = handlerTimeout;
        }

        public Duration getRetention() {
            return retention;
        }

        public void setRetention(Duration retention) {
            this.retention = retention;
        }
    }
}
