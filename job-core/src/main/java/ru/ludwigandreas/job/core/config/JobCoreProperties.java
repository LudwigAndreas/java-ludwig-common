package ru.ludwigandreas.job.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

    /** Schema-management settings. */
    public Liquibase getLiquibase() {
        return liquibase;
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
