package ru.ludwigandreas.export.config;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.export.api.CallIdentity;
import ru.ludwigandreas.export.api.EnrichmentStage;
import ru.ludwigandreas.export.api.ReportDefinition;
import ru.ludwigandreas.export.enrich.EnrichmentSettings;
import ru.ludwigandreas.export.exception.ExportConfigurationException;
import ru.ludwigandreas.restclient.config.AuthTypes;

/**
 * Refuses to start on a configuration that is individually valid and jointly wrong.
 *
 * <p>Bean Validation checks one field at a time, which catches a negative window size and misses
 * every interesting mistake in this module. The failures below are all relationships - between two
 * numbers, between a number and a bean, between a lease and the heartbeat that renews it - and each
 * of them produces behaviour that is intermittent, load-dependent and nearly impossible to
 * attribute weeks after the change that caused it: a report that is fine in staging and exhausts
 * the heap in production, a partner that sees four times the fan-out it was sized for, a run that
 * is reclaimed and executed twice because its lease expired while it was still alive.
 *
 * <p>Every check states what breaks rather than what is wrong, because the person reading the
 * message is deploying at the time and needs to know whether to roll back. Every problem found is
 * reported together, so a misconfigured deployment costs one restart rather than one per mistake.
 */
public class ExportConfigurationValidator {

    /**
     * How much longer a lease must be than the interval that renews it.
     *
     * <p>One renewal per lease leaves no margin: a single slow one - a garbage-collection pause, a
     * database hiccup - lets the lease expire while the run is still writing, another instance
     * claims it, and the report is produced twice with two different files under one run id.
     */
    private static final int HEARTBEAT_SAFETY_FACTOR = 2;

    private static final Logger log = LoggerFactory.getLogger(ExportConfigurationValidator.class);

    private final ExportProperties properties;
    private final Set<String> registeredFormatIds;
    private final Set<String> registeredSinkTypes;
    private final Collection<ReportDefinition<?, ?>> definitions;
    private final RestClientPoolSizes restClients;

    /** Resolves a stage's declared identity against the module default; see {@link CallIdentity}. */
    private final EnrichmentSettings callIdentities;

    /**
     * Creates the validator.
     *
     * @param properties          the bound configuration
     * @param registeredFormatIds ids of the formats whose writer factories are on the context
     * @param registeredSinkTypes names of the sink beans available
     * @param definitions         the registered definitions, for the per-stage checks
     * @param restClients         what is known about a named REST client - its pool size and how it
     *                            authenticates - or nothing when the rest-client starter is absent or
     *                            does not know that name, in which case naming one from a stage is
     *                            itself the error
     */
    public ExportConfigurationValidator(ExportProperties properties,
                                        Set<String> registeredFormatIds,
                                        Set<String> registeredSinkTypes,
                                        Collection<ReportDefinition<?, ?>> definitions,
                                        RestClientPoolSizes restClients) {
        this.properties = properties;
        this.registeredFormatIds = registeredFormatIds;
        this.registeredSinkTypes = registeredSinkTypes;
        this.definitions = definitions;
        this.restClients = restClients;
        this.callIdentities = new EnrichmentSettings(properties.getEnrichment());
    }

    /**
     * Runs every check.
     *
     * @throws ExportConfigurationException listing every problem found
     */
    @PostConstruct
    public void validate() {
        List<String> problems = new ArrayList<>();
        checkPipelineSizing(problems);
        checkLimits(problems);
        checkTempDirectory(problems);
        checkFormats(problems);
        checkSink(problems);
        checkPoller(problems);
        checkWeb(problems);
        checkStages(problems);
        if (!problems.isEmpty()) {
            throw new ExportConfigurationException(
                    "ludwig.export is not a usable configuration:"
                            + problems.stream().map(p -> "\n  - " + p).reduce("", String::concat));
        }
        log.info("Export configuration validated: window {} rows, source page {}, enrichment batch {}"
                        + " at concurrency {}, cache {} entries, {} definitions",
                properties.getWindowSize(), properties.getSourcePageSize(),
                properties.getEnrichment().getBatchSize(), properties.getEnrichment().getConcurrency(),
                properties.getEnrichment().getCacheSize(), definitions.size());
    }

    private void checkPipelineSizing(List<String> problems) {
        int window = properties.getWindowSize();
        int page = properties.getSourcePageSize();
        ExportProperties.Enrichment enrichment = properties.getEnrichment();
        if (page > window) {
            problems.add("source-page-size (" + page + ") is larger than window-size (" + window
                    + "): the engine would hold a page it cannot pass on in one window, so the memory"
                    + " bound the window defines would be the page size instead");
        }
        if (enrichment.getBatchSize() > window) {
            problems.add("enrichment.batch-size (" + enrichment.getBatchSize()
                    + ") is larger than window-size (" + window + "): a batch can never be filled, so"
                    + " every partner call carries at most a window's worth of keys and the batch size"
                    + " has no effect");
        }
        if (enrichment.isCacheEnabled() && enrichment.getCacheSize() < enrichment.getBatchSize()) {
            problems.add("enrichment.cache-size (" + enrichment.getCacheSize()
                    + ") is smaller than enrichment.batch-size (" + enrichment.getBatchSize()
                    + "): one batch would evict the entries of the batch before it, so the cache would"
                    + " cost a lookup per key and return nothing");
        }
    }

    private void checkLimits(List<String> problems) {
        if (properties.getSyncThresholdRows() > properties.getMaxRowsPerRun()) {
            problems.add("sync-threshold-rows (" + properties.getSyncThresholdRows()
                    + ") is above max-rows-per-run (" + properties.getMaxRowsPerRun()
                    + "): every run large enough to be deferred would already have been refused, so"
                    + " nothing would ever run asynchronously");
        }
        for (ReportDefinition<?, ?> definition : definitions) {
            if (definition.getMaxRows() > properties.getMaxRowsPerRun()) {
                log.warn("Report definition {} declares maxRows {} above ludwig.export.max-rows-per-run"
                                + " ({}); the lower estate-wide limit applies",
                        definition.getKey(), definition.getMaxRows(), properties.getMaxRowsPerRun());
            }
        }
        if (properties.getWallClockBudget().isZero() || properties.getWallClockBudget().isNegative()) {
            problems.add("wall-clock-budget must be positive, was: " + properties.getWallClockBudget());
        }
    }

    private void checkTempDirectory(List<String> problems) {
        String configured = properties.getTemp().getDirectory();
        if (configured == null || configured.isBlank()) {
            return;
        }
        try {
            Path directory = Paths.get(configured);
            if (!Files.exists(directory)) {
                problems.add("temp.directory " + configured + " does not exist; the engine writes every"
                        + " report into it and will not create it, because a typo that silently created"
                        + " a directory would put four hundred megabytes somewhere nobody is watching");
            } else if (!Files.isDirectory(directory)) {
                problems.add("temp.directory " + configured + " is not a directory");
            } else if (!Files.isWritable(directory)) {
                problems.add("temp.directory " + configured + " is not writable by this process");
            }
        } catch (InvalidPathException e) {
            problems.add("temp.directory " + configured + " is not a valid path: " + e.getMessage());
        }
    }

    private void checkFormats(List<String> problems) {
        Set<String> enabled = properties.getFormats().getEnabled();
        for (String id : enabled) {
            if (!registeredFormatIds.contains(id)) {
                problems.add("formats.enabled names '" + id + "', for which no ReportWriterFactory is"
                        + " registered (registered: " + String.join(", ", new TreeSet<>(registeredFormatIds))
                        + ")");
            }
        }
        if (!enabled.isEmpty()) {
            for (ReportDefinition<?, ?> definition : definitions) {
                boolean anyAllowed = definition.getAllowedFormats().stream()
                        .anyMatch(format -> enabled.contains(format.id()));
                if (!anyAllowed) {
                    problems.add("definition " + definition.getKey() + " allows only formats that"
                            + " formats.enabled switches off, so it can never be produced");
                }
            }
        }
        String profile = properties.getFormats().getCsv().getProfile();
        if (!"excel".equals(profile) && !"rfc4180".equals(profile)) {
            problems.add("formats.csv.profile must be 'excel' or 'rfc4180', was: " + profile);
        }
    }

    private void checkSink(List<String> problems) {
        String type = properties.getSink().getType();
        if (!registeredSinkTypes.contains(type)) {
            problems.add("sink.type '" + type + "' matches no registered ReportSink (registered: "
                    + String.join(", ", new TreeSet<>(registeredSinkTypes)) + ")");
        }
        if ("s3".equals(type)) {
            String bucket = properties.getSink().getBucket();
            if (bucket == null || bucket.isBlank()) {
                // Checked here rather than left to the sink's constructor so that it is reported
                // alongside every other configuration problem in one message at startup, instead of
                // as the first of several failures discovered one restart at a time.
                problems.add("sink.type is 's3' but sink.bucket is not set: there is nowhere to put a"
                        + " finished report");
            }
        }
        Duration retention = properties.getSink().getRetention();
        Duration linkTtl = properties.getSink().getDownloadLinkTtl();
        if (linkTtl.compareTo(retention) > 0) {
            problems.add("sink.download-link-ttl (" + linkTtl + ") outlives sink.retention (" + retention
                    + "): a link handed to a user would still be valid after the purge deleted the file"
                    + " behind it, so the download would fail rather than expire");
        }
        if (properties.getSink().getPurgeInterval().compareTo(retention) > 0) {
            problems.add("sink.purge-interval (" + properties.getSink().getPurgeInterval()
                    + ") is longer than sink.retention (" + retention + "): outputs would routinely"
                    + " outlive their retention by up to a whole purge interval");
        }
    }

    private void checkPoller(List<String> problems) {
        ExportProperties.Poller poller = properties.getPoller();
        Duration lease = poller.getLeaseDuration();
        Duration heartbeat = poller.getHeartbeatInterval();
        if (heartbeat.multipliedBy(HEARTBEAT_SAFETY_FACTOR).compareTo(lease) > 0) {
            problems.add("poller.heartbeat-interval (" + heartbeat + ") leaves no margin under"
                    + " poller.lease-duration (" + lease + "): one slow renewal would let the lease"
                    + " expire while the run is still writing, and a second instance would produce the"
                    + " same report again. The lease must be at least "
                    + HEARTBEAT_SAFETY_FACTOR + "x the heartbeat");
        }
        if (poller.getInterval().compareTo(lease) >= 0) {
            problems.add("poller.interval (" + poller.getInterval() + ") is at least as long as"
                    + " poller.lease-duration (" + lease + "): a reclaimed run would wait a full poll"
                    + " before anything looked at it again");
        }
        if (poller.getDrainTimeout().compareTo(lease) >= 0) {
            problems.add("poller.drain-timeout (" + poller.getDrainTimeout() + ") is at least as long"
                    + " as poller.lease-duration (" + lease + "): shutdown would still be waiting for a"
                    + " run whose lease another instance had already taken");
        }
        if (poller.getRetryInitialDelay().compareTo(poller.getRetryMaxDelay()) > 0) {
            problems.add("poller.retry-initial-delay (" + poller.getRetryInitialDelay()
                    + ") is longer than poller.retry-max-delay (" + poller.getRetryMaxDelay() + ")");
        }
        // A run outliving its lease is normal and is not a problem: the lease is renewed by the
        // heartbeat for as long as the run is alive, which is the whole point of leasing rather than
        // locking. What would be a problem is a heartbeat with no margin, which is checked above.
    }

    private void checkWeb(List<String> problems) {
        String basePath = properties.getWeb().getBasePath();
        if (!basePath.startsWith("/")) {
            problems.add("web.base-path must start with '/', was: " + basePath);
        }
        if (basePath.endsWith("/")) {
            problems.add("web.base-path must not end with '/', was: " + basePath);
        }
    }

    private void checkStages(List<String> problems) {
        int defaultConcurrency = properties.getEnrichment().getConcurrency();
        for (ReportDefinition<?, ?> definition : definitions) {
            for (EnrichmentStage<?, ?, ?> stage : definition.getStages()) {
                int concurrency = stage.getConcurrency() == null ? defaultConcurrency : stage.getConcurrency();
                String client = stage.getRestClient();
                if (client == null) {
                    continue;
                }
                OptionalInt poolSize = restClients.forClient(client);
                if (poolSize.isEmpty()) {
                    problems.add(definition.getKey() + ": stage " + stage.getName() + " names REST client"
                            + " '" + client + "', which is not configured - the calls would be made by"
                            + " whatever the enricher opens instead, outside the platform's pools,"
                            + " timeouts and circuit breakers");
                    continue;
                }
                checkCallIdentity(definition, stage, client, problems);
                if (concurrency > poolSize.getAsInt()) {
                    problems.add(definition.getKey() + ": stage " + stage.getName() + " asks for"
                            + " concurrency " + concurrency + " but REST client '" + client + "' has a"
                            + " pool of " + poolSize.getAsInt() + ": the extra calls would queue inside"
                            + " the pool, turning a bounded fan-out into an unbounded wait");
                }
            }
        }
    }

    /**
     * Checks that a stage's declared identity is the one its REST client actually sends.
     *
     * <p>These are the same decision written in two files - the definition says whose data the report is
     * scoped to, the deployment says whose credentials reach the partner - and when they disagree nothing
     * fails at run time. A {@code REQUESTER} stage whose client authenticates with this service's own
     * credentials produces a file containing whatever the partner shows this service, which for a
     * customer-facing partner is every customer. That is the failure this check exists for, and it is
     * exactly the kind that is invisible in the output.
     *
     * <p>The reverse direction is checked too, and is a real mistake rather than a harmless one: a
     * {@code SERVICE_ACCOUNT} stage whose client is configured to relay would work while somebody is
     * waiting and fail on every deferred run, because there is no token on a poller thread.
     *
     * <p>Only the pairs this module can actually judge are flagged, which is why the condition names the
     * auth types rather than testing "is not relay". Mutual TLS, a custom authenticator, or anything
     * registered later may well be compatible with either identity, and refusing a deployment over a
     * check that could not be performed would punish somebody for extending the module correctly.
     * {@code none} is judged, and judged as incompatible with {@code REQUESTER}: a client that sends no
     * credential cannot be relaying one.
     */
    private void checkCallIdentity(ReportDefinition<?, ?> definition, EnrichmentStage<?, ?, ?> stage,
                                   String client, List<String> problems) {
        Optional<String> authType = restClients.authTypeOf(client);
        if (authType.isEmpty()) {
            return;
        }
        String type = authType.get();
        CallIdentity declared = callIdentities.callAs(stage);
        boolean relays = AuthTypes.OAUTH2_TOKEN_RELAY.equalsIgnoreCase(type);
        boolean ownCredentials = AuthTypes.OAUTH2_CLIENT_CREDENTIALS.equalsIgnoreCase(type)
                || AuthTypes.BASIC.equalsIgnoreCase(type)
                || AuthTypes.API_KEY.equalsIgnoreCase(type)
                || AuthTypes.BEARER.equalsIgnoreCase(type)
                || AuthTypes.NONE.equalsIgnoreCase(type);
        if (declared == CallIdentity.REQUESTER && ownCredentials) {
            problems.add(definition.getKey() + ": stage " + stage.getName() + " calls as "
                    + CallIdentity.REQUESTER + ", but REST client '" + client + "' is configured with"
                    + " auth.type '" + type + "', which cannot carry the caller's identity. The partner"
                    + " would be asked under this service's own credentials and would answer with"
                    + " everything this service may see, which is not what the stage says the report"
                    + " contains - and nothing in the file would say so. Either configure the client with"
                    + " auth.type '" + AuthTypes.OAUTH2_TOKEN_RELAY + "', or declare the stage "
                    + CallIdentity.SERVICE_ACCOUNT + " and scope the report itself");
        } else if (declared == CallIdentity.SERVICE_ACCOUNT && relays) {
            problems.add(definition.getKey() + ": stage " + stage.getName() + " calls as "
                    + CallIdentity.SERVICE_ACCOUNT + ", but REST client '" + client + "' relays the"
                    + " caller's token. Synchronous runs would work and every deferred run would fail,"
                    + " because a poller thread has no token to relay. Either declare the stage "
                    + CallIdentity.REQUESTER + ", or give the client its own credentials"
                    + " (auth.type: " + AuthTypes.OAUTH2_CLIENT_CREDENTIALS + ")");
        }
    }
}
