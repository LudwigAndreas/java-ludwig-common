package ru.ludwigandreas.usersettings.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the settings engine, under {@code ludwig.user-settings}.
 *
 * <p><b>Neither mode is on by default.</b> {@code owner.enabled} and {@code projection.enabled} are
 * both false, and a service has to say which one it is. There is no sensible default here: guessing
 * owner would make a service that only reads start writing tables it does not own, and guessing
 * projection would make a service that owns its users' settings silently refuse every write. Failing
 * to start with "say which mode" is the honest third option.
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "ludwig.user-settings")
public class UserSettingsProperties {

    /** Master switch for the whole module's autoconfiguration. */
    private boolean enabled = true;

    /**
     * Tenant used when the caller has none, which is the normal case in a single-tenant deployment.
     *
     * <p>Set it to empty in a multi-tenant deployment. A caller with no tenant then gets a refused
     * lookup instead of everybody's rows being filed under one shared key - which is the failure this
     * property is most able to cause and the reason it is documented rather than quietly defaulted.
     */
    private String defaultTenant = "default";

    @Valid
    private final Owner owner = new Owner();

    @Valid
    private final Projection projection = new Projection();

    @Valid
    private final Cache cache = new Cache();

    @Valid
    private final Access access = new Access();

    @Valid
    private final Web web = new Web();

    @Valid
    private final Retention retention = new Retention();

    @Valid
    private final Liquibase liquibase = new Liquibase();

    @Valid
    private final Metrics metrics = new Metrics();

    /**
     * Deployment-wide defaults: setting key to encoded value.
     *
     * <p>The keys contain dots, so they need bracket notation in YAML:
     * {@code ludwig.user-settings.platform-defaults[user.timezone]: Europe/Moscow}. The values are in
     * the same encoding a converter produces, because the same converter reads them back.
     */
    private Map<String, String> platformDefaults = new LinkedHashMap<>();

    /** Per-tenant defaults: tenant id, then setting key to encoded value. */
    private Map<String, Map<String, String>> tenantDefaults = new LinkedHashMap<>();

    /** Owner mode: this service owns the tables and serves writes. */
    @Getter
    @Setter
    public static class Owner {

        private boolean enabled;

        /**
         * Publish every change through the transactional outbox.
         *
         * <p>Requires {@code outbox-spring-boot-starter}; without it the module falls back to
         * publishing nothing rather than failing, because a service that owns settings nobody
         * projects has no reason to run an outbox.
         */
        private boolean publishEvents = true;
    }

    /** Projection mode: this service keeps a read-only replica of another service's settings. */
    @Getter
    @Setter
    public static class Projection {

        private boolean enabled;

        /** The owner's change topic. Also referenced directly by the listener's annotation. */
        @NotBlank
        private String topic = "user.settings";

        /**
         * Consumer group. Every service keeps its own replica and therefore needs its own group -
         * sharing one would mean each service saw only a share of the events.
         */
        private String groupId;

        /**
         * Recorded as {@code source_system} on projected consent rows, so a reader can tell evidence
         * this deployment witnessed from evidence it was told about.
         */
        private String sourceSystem = "user-settings-owner";
    }

    /** The resolved-settings cache. */
    @Getter
    @Setter
    public static class Cache {

        private boolean enabled = true;

        /**
         * How long a resolved set is kept.
         *
         * <p>Minutes, not seconds. Unlike the authority cache - where the TTL is the window a revoked
         * role keeps working, and therefore a security decision - a stale setting means a user
         * briefly sees an old preference, with no privilege attached. Eviction on write and on
         * projected events is the consistency mechanism; this is the backstop for when one is missed.
         */
        @NotNull
        private Duration ttl = Duration.ofMinutes(5);

        /** Upper bound on cached subjects, so a burst of one-off callers cannot grow the heap. */
        @Positive
        private long maximumSize = 10_000;
    }

    /** Who may read and write whose settings. */
    @Getter
    @Setter
    public static class Access {

        /**
         * Authority that allows reading and writing another subject's settings. Accepted with or
         * without the {@code ROLE_} prefix.
         */
        @NotBlank
        private String adminAuthority = "ROLE_SETTINGS_ADMIN";

        /**
         * Allow calls with no authenticated principal - queue workers, scheduled jobs, the projection
         * consumer.
         *
         * <p>True by default because an HTTP request cannot reach a service without the security
         * filter chain establishing a principal first, so an empty security context means the call
         * came from inside. Set it false in a deployment that wants even in-process code to present a
         * system principal, and give the background paths one.
         */
        private boolean allowUnauthenticated = true;

        /**
         * Allow a peer service to resolve any subject's settings.
         *
         * <p>True by default. The self-or-admin rule is about people - it stops one user reading
         * another's preferences - and a service authenticated by its own workload identity is never
         * the subject of a setting. What authorizes such a call is the calling service's own policy
         * for the endpoint it invoked. Set false to hold peers to the administrative authority too.
         */
        private boolean allowServicePrincipals = true;
    }

    /** The shipped REST controllers, which are off by default. */
    @Getter
    @Setter
    public static class Web {

        /**
         * Off by default, deliberately. A service has to be able to own its own API shape - its own
         * paths, its own DTOs, its own versioning - and a starter that mounted endpoints without
         * being asked would be making that decision for it.
         */
        private boolean enabled;

        /** Where the self-service endpoints are mounted. */
        private String basePath = "/me/settings";

        /** Where the administrative endpoints are mounted. */
        private String adminBasePath = "/admin/settings";

        /**
         * Mounts the backfill endpoint. Off even when the other administrative endpoints are on.
         *
         * <p>The rest of the administrative surface reads and writes one subject at a time. This one
         * republishes the estate: an unguarded call can put millions of events through the outbox and
         * every projection behind it. That is a fine thing for an operator with a runbook to do and a
         * poor thing to leave reachable by default, so it gets its own switch rather than riding on
         * {@code web.enabled}. The {@code SettingsBackfillService} bean exists either way, so a service
         * that prefers to drive it from a job or a scheduled task needs nothing turned on at all.
         */
        private boolean backfillEnabled;
    }

    /** Purging the tables that grow without bound. */
    @Getter
    @Setter
    public static class Retention {

        /** Off by default: deleting rows on a timer is a decision an operator makes, not a default. */
        private boolean enabled;

        /** How long a change-trail entry is kept. */
        @NotNull
        private Duration audit = Duration.ofDays(365);

        /**
         * How long a tombstone is kept after the value was reset.
         *
         * <p>It only has to outlive the window in which a late event could still arrive for that
         * setting, which is bounded by the broker's retention rather than by anything here. Thirty
         * days is comfortably longer than a typical topic retention.
         */
        @NotNull
        private Duration tombstones = Duration.ofDays(30);

        /** Rows removed per pass, so a purge never takes a long lock on a table being written to. */
        @Positive
        private int batchSize = 500;

        /** How often the purge runs. */
        @NotNull
        private Duration interval = Duration.ofHours(1);
    }

    /** The shipped schema. */
    @Getter
    @Setter
    public static class Liquibase {

        /**
         * Apply the shipped changelog as an independent {@code SpringLiquibase}. Turn this off and
         * include {@code classpath:db/changelog/user-settings/user-settings-changelog.xml} from the
         * application's own master changelog when migration order across modules matters.
         */
        private boolean enabled = true;
    }

    /** Cache, resolution and write instrumentation. */
    @Getter
    @Setter
    public static class Metrics {

        private boolean enabled = true;
    }
}
