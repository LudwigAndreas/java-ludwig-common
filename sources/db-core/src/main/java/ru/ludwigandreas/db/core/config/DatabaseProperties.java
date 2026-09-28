package ru.ludwigandreas.db.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ludwig.db")
public class DatabaseProperties {

    /**
     * Whether Spring Data JPA auditing ({@code @CreatedDate}/{@code @CreatedBy}/etc.) is enabled.
     */
    private boolean auditingEnabled = true;

    /**
     * Default page size used by {@code PageableUtils.of} when the caller doesn't request one.
     */
    private int defaultPageSize = 20;

    /**
     * Upper bound page size enforced by {@code PageableUtils.of}.
     */
    private int maxPageSize = 200;

    private final Metrics metrics = new Metrics();

    public Metrics getMetrics() {
        return metrics;
    }

    public boolean isAuditingEnabled() {
        return auditingEnabled;
    }

    public void setAuditingEnabled(boolean auditingEnabled) {
        this.auditingEnabled = auditingEnabled;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public static class Metrics {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
