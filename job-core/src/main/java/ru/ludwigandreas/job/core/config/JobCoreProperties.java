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
}
