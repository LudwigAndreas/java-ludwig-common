package ru.ludwigandreas.outbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "ludwig.outbox")
public class OutboxProperties {

    /** Master switch for the whole module's autoconfiguration. */
    private boolean enabled = true;

    private final Polling polling = new Polling();
    private final Processing processing = new Processing();
    private final Retry retry = new Retry();
    private final Ordering ordering = new Ordering();
    private final Idempotency idempotency = new Idempotency();
    private final DeadLetter deadLetter = new DeadLetter();
    private final Audit audit = new Audit();
    private final Metrics metrics = new Metrics();
    private final Liquibase liquibase = new Liquibase();
    private final Rest rest = new Rest();
    private final Kafka kafka = new Kafka();

    /** Used when an event doesn't match any entry in {@link #routes} and sets no explicit route. */
    private Route defaultRoute;

    /** Route name -> transport/destination/event-type-match config, looked up by {@code OutboxEvent.eventType}. */
    private Map<String, Route> routes = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Polling getPolling() {
        return polling;
    }

    public Processing getProcessing() {
        return processing;
    }

    public Retry getRetry() {
        return retry;
    }

    public Ordering getOrdering() {
        return ordering;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public DeadLetter getDeadLetter() {
        return deadLetter;
    }

    public Audit getAudit() {
        return audit;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public Liquibase getLiquibase() {
        return liquibase;
    }

    public Rest getRest() {
        return rest;
    }

    public Kafka getKafka() {
        return kafka;
    }

    public Route getDefaultRoute() {
        return defaultRoute;
    }

    public void setDefaultRoute(Route defaultRoute) {
        this.defaultRoute = defaultRoute;
    }

    public Map<String, Route> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, Route> routes) {
        this.routes = routes;
    }

    public static class Polling {

        private boolean enabled = true;
        private Duration fixedDelay = Duration.ofSeconds(5);
        private Duration initialDelay = Duration.ofSeconds(5);
        private int batchSize = 100;

        /** Identifies this instance in {@code locked_by}; defaults to hostname + a random suffix if unset. */
        private String lockOwner;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getFixedDelay() {
            return fixedDelay;
        }

        public void setFixedDelay(Duration fixedDelay) {
            this.fixedDelay = fixedDelay;
        }

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public String getLockOwner() {
            return lockOwner;
        }

        public void setLockOwner(String lockOwner) {
            this.lockOwner = lockOwner;
        }
    }

    public static class Processing {

        /** A row stuck PROCESSING longer than this is assumed abandoned by a dead poller and reclaimed. */
        private Duration staleTimeout = Duration.ofMinutes(5);
        private Duration staleReclaimFixedDelay = Duration.ofMinutes(1);

        public Duration getStaleTimeout() {
            return staleTimeout;
        }

        public void setStaleTimeout(Duration staleTimeout) {
            this.staleTimeout = staleTimeout;
        }

        public Duration getStaleReclaimFixedDelay() {
            return staleReclaimFixedDelay;
        }

        public void setStaleReclaimFixedDelay(Duration staleReclaimFixedDelay) {
            this.staleReclaimFixedDelay = staleReclaimFixedDelay;
        }
    }

    public static class Retry {

        private int maxAttempts = 10;
        private Duration initialInterval = Duration.ofSeconds(1);
        private double multiplier = 2.0;
        private Duration maxInterval = Duration.ofMinutes(5);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getInitialInterval() {
            return initialInterval;
        }

        public void setInitialInterval(Duration initialInterval) {
            this.initialInterval = initialInterval;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public Duration getMaxInterval() {
            return maxInterval;
        }

        public void setMaxInterval(Duration maxInterval) {
            this.maxInterval = maxInterval;
        }
    }

    public static class Ordering {

        /** When false, {@code OutboxEvent.orderingKey} is ignored and stored as null (max poll throughput). */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Idempotency {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class DeadLetter {

        /** When false, messages that exhaust retries stay FAILED instead of moving to DEAD_LETTER. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Audit {

        private boolean persistHistory = false;

        public boolean isPersistHistory() {
            return persistHistory;
        }

        public void setPersistHistory(boolean persistHistory) {
            this.persistHistory = persistHistory;
        }
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

    public static class Liquibase {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Rest {

        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);

        /** Logical endpoint name -> URL, resolved when a message's {@code destination} isn't itself a URL. */
        private Map<String, String> endpoints = new LinkedHashMap<>();

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }

        public Map<String, String> getEndpoints() {
            return endpoints;
        }

        public void setEndpoints(Map<String, String> endpoints) {
            this.endpoints = endpoints;
        }
    }

    public static class Kafka {

        private Duration sendTimeout = Duration.ofSeconds(10);

        public Duration getSendTimeout() {
            return sendTimeout;
        }

        public void setSendTimeout(Duration sendTimeout) {
            this.sendTimeout = sendTimeout;
        }
    }

    public static class Route {

        private String transport;
        private String destination;

        /** Only used for entries in {@link OutboxProperties#routes}: matches when it contains the event's eventType. */
        private List<String> eventTypes = List.of();

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public String getDestination() {
            return destination;
        }

        public void setDestination(String destination) {
            this.destination = destination;
        }

        public List<String> getEventTypes() {
            return eventTypes;
        }

        public void setEventTypes(List<String> eventTypes) {
            this.eventTypes = eventTypes;
        }
    }
}
