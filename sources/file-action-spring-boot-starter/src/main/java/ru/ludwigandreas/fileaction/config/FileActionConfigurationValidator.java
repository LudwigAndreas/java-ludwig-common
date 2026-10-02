package ru.ludwigandreas.fileaction.config;

import jakarta.validation.Validator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.util.unit.DataSize;
import ru.ludwigandreas.fileaction.api.CommitPolicy;
import ru.ludwigandreas.fileaction.api.DocumentHandler;
import ru.ludwigandreas.fileaction.api.ErrorReportFormat;
import ru.ludwigandreas.fileaction.api.ExecutionMode;
import ru.ludwigandreas.fileaction.api.FileAction;
import ru.ludwigandreas.fileaction.api.FileActionHandler;
import ru.ludwigandreas.fileaction.api.FileScanner;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.api.ScanningMode;
import ru.ludwigandreas.fileaction.engine.FileActionRegistry;
import ru.ludwigandreas.fileaction.engine.ResolvedAction;
import ru.ludwigandreas.fileaction.engine.RowMaterialiser;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.format.ReadBudget;
import ru.ludwigandreas.fileaction.format.SourceFormat;

/**
 * Reconciles the configuration and the code, and refuses to start the application when they disagree.
 *
 * <h2>Why a validator rather than a sensible default</h2>
 *
 * <p>Every refusal below is a misconfiguration whose symptom, left alone, is business data: a commit policy
 * nobody declared, an inline action sized for a hundred thousand rows, an authority configured while the
 * security starter is absent, a scanner required and missing. None of them is visible in a test - a test
 * configures what it needs - and all of them are visible to the first user who drags a file in.
 *
 * <p>This is the same shape as {@code file-ingest}'s {@code FileIngestConfigurationValidator}, for the same
 * reason: half of what makes a module like this correct is a configuration fact, and neither ArchUnit nor
 * Checkstyle can see one. Startup is the third enforcement point, and it is the only one available here.
 *
 * <p>Every message names the action, the property and the acceptable values, because a startup failure that
 * does not say what to change is an outage with extra steps.
 */
public class FileActionConfigurationValidator {

    private final FileActionProperties properties;
    private final Map<String, FileActionHandler<?>> handlersByAction;
    /**
     * The deployment's scanner, or null.
     *
     * <p>A nullable field rather than an {@code Optional} one, per the platform's Optional convention: an
     * {@code Optional} field is a second null to check, and a null Optional and an empty one both occur.
     */
    private final FileScanner scanner;
    private final Validator validator;
    private final boolean securityPresent;
    private final MessageKeyAvailability messageKeys;

    /**
     * Prepares the validator.
     *
     * @param properties       the configured tree
     * @param handlers         every {@link FileAction}-annotated bean, by the action it names
     * @param scanner          the configured scanner, or null when the deployment has none
     * @param validator        the Bean Validation validator, if the application has one
     * @param securityPresent  whether {@code security-spring-boot-starter} is on the classpath
     * @param messageKeys      how to ask whether a message key resolves in every shipped locale
     */
    public FileActionConfigurationValidator(FileActionProperties properties,
                                            Map<String, FileActionHandler<?>> handlers,
                                            FileScanner scanner, Validator validator,
                                            boolean securityPresent,
                                            MessageKeyAvailability messageKeys) {
        this.properties = properties;
        this.handlersByAction = handlers;
        this.scanner = scanner;
        this.validator = validator;
        this.securityPresent = securityPresent;
        this.messageKeys = messageKeys;
    }

    /** Asks whether a message key resolves, so the validator need not know how messages are looked up. */
    @FunctionalInterface
    public interface MessageKeyAvailability {

        /**
         * Whether a key resolves in every locale the module ships.
         *
         * @param key the message key
         * @return true when it does
         */
        boolean resolves(String key);
    }

    /**
     * Resolves and validates every action.
     *
     * @return the registry the rest of the module reads
     * @throws IllegalStateException with every problem found, not just the first
     */
    public FileActionRegistry validate() {
        List<String> problems = new ArrayList<>();
        validateScanning(problems);
        validateStorage(problems);
        validateUnmatchedHandlers(problems);
        validateProblemCodes(problems);

        List<ResolvedAction<?>> resolved = new ArrayList<>();
        for (Map.Entry<String, FileActionProperties.Action> entry : properties.getActions().entrySet()) {
            String name = entry.getKey();
            FileActionProperties.Action configured = entry.getValue();
            FileActionHandler<?> handler = handlersByAction.get(name);
            if (handler == null) {
                problems.add("action '" + name + "' is configured under ludwig.file-action.actions but no"
                        + " bean is annotated @FileAction(\"" + name + "\"). An action is two halves and"
                        + " both must be present");
                continue;
            }
            resolveOne(name, configured, handler, problems).ifPresent(resolved::add);
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("file-action configuration is not usable:"
                    + problems.stream().map(problem -> System.lineSeparator() + "  - " + problem)
                            .reduce("", String::concat));
        }
        return new FileActionRegistry(resolved);
    }

    private void validateScanning(List<String> problems) {
        if (properties.getScanning().getMode() == ScanningMode.REQUIRED && scanner == null) {
            problems.add("ludwig.file-action.scanning.mode is REQUIRED but no FileScanner bean is present."
                    + " This module ships no scanner on purpose - which scanner an estate runs is a"
                    + " deployment decision - and it fails closed so that having none is a choice rather"
                    + " than an accident. Supply a FileScanner bean, or set"
                    + " ludwig.file-action.scanning.mode=DISABLED to accept unscanned user files");
        }
    }

    private void validateStorage(List<String> problems) {
        if (isBlank(properties.getStorage().getUploads())) {
            problems.add("ludwig.file-action.storage.uploads is not set. There is no default because the"
                    + " only possible one is a local directory, which works on a laptop and silently writes"
                    + " a production deployment's uploads to a pod's ephemeral disk");
        }
        if (isBlank(properties.getStorage().getArtifacts())) {
            problems.add("ludwig.file-action.storage.artifacts is not set. The bound-row artifact a"
                    + " CONFIRM-mode action applies and the reject report a user downloads both live there");
        }
        if (properties.getStorage().getRetention().isNegative()
                || properties.getStorage().getRetention().isZero()) {
            problems.add("ludwig.file-action.storage.retention must be positive, was "
                    + properties.getStorage().getRetention());
        }
    }

    private void validateUnmatchedHandlers(List<String> problems) {
        Set<String> configured = properties.getActions().keySet();
        for (String declared : new TreeSet<>(handlersByAction.keySet())) {
            if (!configured.contains(declared)) {
                problems.add("a bean is annotated @FileAction(\"" + declared + "\") but nothing is"
                        + " configured under ludwig.file-action.actions." + declared + ". Configured"
                        + " actions are " + configured);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <R> Optional<ResolvedAction<?>> resolveOne(String name,
                                                       FileActionProperties.Action configured,
                                                       FileActionHandler<R> handler,
                                                       List<String> problems) {
        int before = problems.size();
        FileActionProperties.ActionDefaults defaults = properties.getDefaults();

        CommitPolicy commitPolicy = configured.getCommitPolicy();
        if (commitPolicy == null) {
            problems.add("action '" + name + "' does not declare ludwig.file-action.actions." + name
                    + ".commit-policy. There is no default: ALL_OR_NOTHING refuses a four-hundred-row file"
                    + " over one typo and PER_ROW applies two thirds of a journal entry, and which of those"
                    + " is wrong depends on the domain rather than on anything this module can see. One of "
                    + List.of(CommitPolicy.values()));
        } else if (handler instanceof DocumentHandler<R> && !commitPolicy.isAllOrNothing()) {
            problems.add("action '" + name + "' declares commit-policy " + commitPolicy + " but its handler"
                    + " is a DocumentHandler, which applies every row as one business fact and is therefore"
                    + " all-or-nothing by construction. Either declare ALL_OR_NOTHING or make the handler a"
                    + " RowHandler");
        }

        int maxRows = orDefault(configured.getMaxRows(), defaults.getMaxRows());
        if (maxRows > defaults.getMaxRowsLimit()) {
            problems.add("action '" + name + "' has max-rows " + maxRows + ", above the module's declared"
                    + " ceiling of " + defaults.getMaxRowsLimit() + ". A file that large is a scheduled bulk"
                    + " import rather than something a person is waiting for - use"
                    + " file-ingest-spring-boot-starter, or raise"
                    + " ludwig.file-action.defaults.max-rows-limit knowing that this module's heap budget is"
                    + " measured against it");
        }
        if (configured.getExecution() == ExecutionMode.INLINE
                && maxRows > properties.getInline().getMaxRows()) {
            problems.add("action '" + name + "' is INLINE with max-rows " + maxRows + ", above"
                    + " ludwig.file-action.inline.max-rows (" + properties.getInline().getMaxRows() + ")."
                    + " An inline action sized that large is a request timeout and a held connection,"
                    + " discovered in production. Either set execution: DEFERRED or raise the ceiling"
                    + " deliberately");
        }

        if (configured.getRequiredAuthority() != null && !securityPresent) {
            problems.add("action '" + name + "' declares required-authority '"
                    + configured.getRequiredAuthority() + "' but security-spring-boot-starter is not on the"
                    + " classpath, so nothing would check it. Add the starter, or remove the authority and"
                    + " accept that the endpoint is open");
        }

        double rejectThreshold = orDefault(configured.getRejectThreshold(), defaults.getRejectThreshold());
        if (rejectThreshold < 0 || rejectThreshold > 1) {
            problems.add("action '" + name + "' has reject-threshold " + rejectThreshold
                    + ", which is a fraction of the rows read and must be between 0 and 1");
        }

        int batchSize = orDefault(configured.getBatchSize(), defaults.getBatchSize());
        if (batchSize <= 0) {
            problems.add("action '" + name + "' has batch-size " + batchSize + ", which must be positive");
        }

        RowBinding<R> binding = handler.binding();
        if (binding == null) {
            problems.add("action '" + name + "' has a handler whose binding() returned null");
            return Optional.empty();
        }

        Set<SourceFormat> formats = new LinkedHashSet<>(
                orDefault(configured.getFormats(), defaults.getFormats()));
        if (formats.isEmpty()) {
            problems.add("action '" + name + "' accepts no formats");
        }
        ErrorReportFormat errorReport = orDefault(configured.getErrorReport(), defaults.getErrorReport());
        Duration confirmTtl = orDefault(configured.getConfirmTtl(), defaults.getConfirmTtl());
        DataSize maxSize = orDefault(configured.getMaxSize(), defaults.getMaxSize());
        FileActionProperties.Archive archive = orDefault(configured.getArchive(), defaults.getArchive());

        if (problems.size() != before) {
            return Optional.empty();
        }
        ReadBudget budget = new ReadBudget(maxSize.toBytes(), maxRows, archive.getMaxEntries(),
                archive.getMinInflateRatio(), archive.getMaxUncompressed().toBytes(),
                archive.getMaxSharedStrings().toBytes(), archive.getMaxCellCharacters(),
                archive.getReadTimeout());
        return Optional.of(new ResolvedAction<>(name, handler, binding,
                new RowMaterialiser<>(binding, validator), configured.getMode(),
                configured.getExecution(), commitPolicy, formats, maxSize.toBytes(), batchSize,
                orDefault(configured.getRejectSample(), defaults.getRejectSample()), rejectThreshold,
                confirmTtl, errorReport, configured.getRequiredAuthority(), budget));
    }

    /**
     * Checks that every message code this module can emit resolves in every locale it ships.
     *
     * <h2>What this checks, and what it deliberately does not</h2>
     *
     * <p>It checks {@code FileActionProblemCodes}: every refusal and every cell-level reject this module can
     * produce. A deployment that overrides the bundle - to reword a message for its own users, which is a
     * reasonable thing to do - can drop a key, and the symptom is a reject report with a raw key in it, seen
     * by a user and not by the deployment.
     *
     * <p>It does <em>not</em> check a key per binding column, and the reason is worth recording because the
     * change that introduced this module said it would. A {@link RowBinding} column declares no message key:
     * its label in a reject is its declared header, which is the text the user is looking at in their own
     * file and is a better label than any translation of it would be. So there is no key to check. Demanding
     * one would make adding a column a two-file change and would put a translated column name in a message
     * about a column the user sees under a different name.
     *
     * <p>It also cannot check a code a handler invents in {@code RowOutcome.rejected}. Those are not
     * enumerable from here - they are string literals inside a consuming service's method - and that gap is
     * listed in {@code docs/harness-enforcement.md} rather than papered over.
     */
    private void validateProblemCodes(List<String> problems) {
        if (messageKeys == null) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String code : FileActionProblemCodes.all()) {
            if (!messageKeys.resolves(code)) {
                missing.add(code);
            }
        }
        if (!missing.isEmpty()) {
            problems.add("these message codes do not resolve in every locale this module ships: " + missing
                    + ". A user would see the raw key in a reject report. If a deployment has overridden"
                    + " i18n/ludwig-file-action-messages, every key in FileActionProblemCodes must still"
                    + " resolve");
        }
    }

    private static <T> T orDefault(T configured, T fallback) {
        return configured == null ? fallback : configured;
    }

    private static int orDefault(Integer configured, int fallback) {
        return configured == null ? fallback : configured;
    }

    private static double orDefault(Double configured, double fallback) {
        return configured == null ? fallback : configured;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
