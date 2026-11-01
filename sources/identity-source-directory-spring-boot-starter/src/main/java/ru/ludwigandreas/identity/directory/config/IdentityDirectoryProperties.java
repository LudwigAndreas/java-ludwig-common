package ru.ludwigandreas.identity.directory.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The directory feed's configuration, under {@code ludwig.identity.directory}.
 */
@Validated
@ConfigurationProperties(prefix = "ludwig.identity.directory")
public class IdentityDirectoryProperties {

    /** Per-source schedules, by source name. A directory whose source is not listed runs on the defaults. */
    private Map<String, @Valid Source> sources = new LinkedHashMap<>();

    @Valid
    private final Liquibase liquibase = new Liquibase();

    public Map<String, Source> getSources() {
        return sources;
    }

    public void setSources(Map<String, Source> sources) {
        this.sources = sources;
    }

    public Liquibase getLiquibase() {
        return liquibase;
    }

    /**
     * A source's settings, or the defaults.
     *
     * @param source a source name
     * @return its settings
     */
    public Source settingsFor(String source) {
        return sources.getOrDefault(source, new Source());
    }

    /** One directory-fed source's schedule. */
    public static class Source {

        /** How long after start the first sync runs; a source with no watermark is swept by it. */
        @NotNull
        private Duration initialDelay = Duration.ofSeconds(15);

        /** How long after one incremental sync ends the next starts. */
        @NotNull
        private Duration syncInterval = Duration.ofMinutes(5);

        /** How long after one sweep ends the next starts. */
        @NotNull
        private Duration sweepInterval = Duration.ofDays(1);

        /** The page size asked of the directory. */
        @Min(1)
        @Max(10_000)
        private int pageSize = 500;

        /** The run lease a sync or sweep holds; longer than the longest run, or a second replica starts one. */
        @NotNull
        private Duration lease = Duration.ofHours(1);

        public Duration getInitialDelay() {
            return initialDelay;
        }

        public void setInitialDelay(Duration initialDelay) {
            this.initialDelay = initialDelay;
        }

        public Duration getSyncInterval() {
            return syncInterval;
        }

        public void setSyncInterval(Duration syncInterval) {
            this.syncInterval = syncInterval;
        }

        public Duration getSweepInterval() {
            return sweepInterval;
        }

        public void setSweepInterval(Duration sweepInterval) {
            this.sweepInterval = sweepInterval;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }

        public Duration getLease() {
            return lease;
        }

        public void setLease(Duration lease) {
            this.lease = lease;
        }
    }

    /** The state table's changelog. */
    public static class Liquibase {

        /**
         * Apply the shipped changelog as an independent {@code SpringLiquibase}. Turn this off and include
         * {@code classpath:db/changelog/identity-directory/identity-directory-changelog.xml} from the application's
         * own master changelog when migration order across modules matters.
         */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
