package ru.ludwigandreas.usersettings.config;

import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import ru.ludwigandreas.usersettings.repository.UserConsentRepository;
import ru.ludwigandreas.usersettings.repository.UserSettingValueRepository;
import ru.ludwigandreas.usersettings.retention.SettingsRetentionService;

/**
 * Purges old tombstones on a schedule.
 *
 * <p>The change trail is not purged here any more: it lives in {@code audit_event} and is purged by
 * {@code audit-spring-boot-starter}'s per-category retention job, under {@code ludwig.audit.retention}.
 *
 * <p>Off by default. Deleting rows on a timer is a decision an operator makes against a written
 * retention policy, not something a library should start doing because it was added to a pom.
 *
 * <p>Consents are never purged here; see {@link SettingsRetentionService} for why that one is
 * deliberately left to an explicit call.
 */
@Slf4j
@AutoConfiguration(after = UserSettingsAutoConfiguration.class)
@ConditionalOnProperty(prefix = "ludwig.user-settings.retention", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(UserSettingsProperties.class)
@EnableScheduling
public class UserSettingsRetentionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SettingsRetentionService settingsRetentionService(UserSettingValueRepository values,
                                                              UserConsentRepository consents,
                                                              Clock userSettingsClock) {
        return new SettingsRetentionService(values, consents, userSettingsClock);
    }

    @Bean
    @ConditionalOnMissingBean
    public SettingsRetentionScheduler settingsRetentionScheduler(SettingsRetentionService service,
                                                                  UserSettingsProperties properties) {
        return new SettingsRetentionScheduler(service, properties);
    }

    /**
     * Runs one batch of each purge per tick.
     *
     * <p>One batch rather than looping until nothing is left: a backlog is cleared over several ticks
     * instead of in one long transaction, which keeps the purge off the critical path of whatever
     * else the database is doing. The interval is a fixed <em>delay</em>, so a slow pass never
     * overlaps the next one.
     */
    @RequiredArgsConstructor
    public static class SettingsRetentionScheduler {

        private final SettingsRetentionService service;
        private final UserSettingsProperties properties;

        /** One batch of each purge; a backlog is cleared over several ticks rather than in one lock. */
        @Scheduled(fixedDelayString = "${ludwig.user-settings.retention.interval:PT1H}")
        public void purge() {
            UserSettingsProperties.Retention retention = properties.getRetention();
            int tombstones = service.purgeTombstones(retention.getTombstones(), retention.getBatchSize());
            if (tombstones == retention.getBatchSize()) {
                log.info("Settings retention pass filled a batch (tombstones={}); the backlog will"
                        + " continue on the next tick", tombstones);
            }
        }
    }
}
