package ru.ludwigandreas.fileaction.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.ludwigandreas.audit.Actor;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.CorrelationProvider;
import ru.ludwigandreas.fileaction.api.ActionMode;
import ru.ludwigandreas.fileaction.api.ExecutionMode;
import ru.ludwigandreas.fileaction.api.FileActionContext;
import ru.ludwigandreas.fileaction.api.FileActionState;
import ru.ludwigandreas.fileaction.api.FileScanner;
import ru.ludwigandreas.fileaction.api.ScanOutcome;
import ru.ludwigandreas.fileaction.api.ScanningMode;
import ru.ludwigandreas.fileaction.audit.FileActionAuditActions;
import ru.ludwigandreas.fileaction.audit.FileActionAuditEvent;
import ru.ludwigandreas.fileaction.entity.FileActionSubmissionEntity;
import ru.ludwigandreas.fileaction.exception.FileActionProblemCodes;
import ru.ludwigandreas.fileaction.exception.FileActionForbiddenException;
import ru.ludwigandreas.fileaction.exception.FileRejectedException;
import ru.ludwigandreas.fileaction.exception.SubmissionNotFoundException;
import ru.ludwigandreas.fileaction.exception.SubmissionStateException;
import ru.ludwigandreas.fileaction.format.CoercionContext;
import ru.ludwigandreas.fileaction.format.FormatSniffer;
import ru.ludwigandreas.fileaction.format.SourceFormat;
import ru.ludwigandreas.fileaction.format.xlsx.write.TemplateWriter;
import ru.ludwigandreas.fileaction.repository.FileActionRowRejectRepository;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.PutOptions;
import ru.ludwigandreas.webcore.preference.UserPreferences;
import ru.ludwigandreas.webcore.problem.ProblemStatus;

/**
 * The whole path from a submitted file to a business action.
 *
 * <h2>The order of the first four steps is the design</h2>
 *
 * <pre>
 *   1. spool and hash   one pass over the request body, refused the moment it exceeds the ceiling
 *   2. store            ObjectStore.put, before anything parses it
 *   3. claim            IdempotencyStore on the content hash
 *   4. record           the submission row, before the read starts
 * </pre>
 *
 * <p>Nothing parses the file until all four have happened, and that is not an implementation detail. A request
 * that has not stored its input cannot be retried, cannot produce an error report that cites the original,
 * cannot answer a dispute three days later, and cannot survive the pod being evicted mid-parse while the user's
 * browser holds an open connection. A request that has not claimed will create the orders twice when somebody
 * double-clicks the drop zone, which is the normal case rather than an edge one.
 *
 * <h2>Why the idempotency filter is not used</h2>
 *
 * <p>{@code IdempotencyStore} is called directly. {@code IdempotencyFilter} buffers the whole request body to
 * fingerprint it, and that module's own {@code CachedBodyRequest} javadoc says an endpoint receiving bodies
 * large enough to matter is not one where it should be reading them. A twenty-five megabyte multipart is such a
 * body. The hash this module is already computing while streaming serves the same purpose at no extra pass.
 */
public class FileActionService {

    private static final Logger LOG = LoggerFactory.getLogger(FileActionService.class);

    private final FileActionRegistry registry;
    private final FileActionSettings settings;
    private final ObjectStore objectStore;
    private final FileActionSubmissionRepository submissions;
    private final FileActionRowRejectRepository rejects;
    private final TemplateWriter templates;
    private final SubmissionStore store;
    private final BindingPass bindingPass;
    private final ApplyPass applyPass;
    private final BoundRowStore boundRows;
    private final FormatSniffer sniffer;
    private final AuditSink audit;
    private final ActorResolver actors;
    private final CorrelationProvider correlation;
    /**
     * The deployment's scanner, or null.
     *
     * <p>A nullable field rather than an {@code Optional} one: the platform's Optional rule forbids it as a field
     * or a parameter type, and the reason it is a rule rather than a preference is that an {@code Optional} field
     * is a second null to check - a null Optional and an empty one both occur and mean different things to
     * whoever wrote the check.
     */
    private final FileScanner scanner;

    /**
     * Whether the caller holds an action's declared authority, or null when no action declares one.
     *
     * <p>Null is safe here only because {@code FileActionConfigurationValidator} refuses to start an application
     * that declares an authority with no security present - so a null checker and a configured authority cannot
     * coexist. The guard below defends the invariant anyway rather than trusting it.
     */
    private final AuthorityChecker authorities;
    private final ErrorReportPublisher reports;
    private final Clock clock;

    /**
     * Wires the service.
     *
     * @param registry    the resolved actions
     * @param settings    the resolved module-wide settings
     * @param objectStore the platform's one bucket client
     * @param submissions the submission table
     * @param rejects     the bounded reject sample
     * @param templates   the template generator
     * @param store       every write to this module's tables
     * @param bindingPass the read-and-bind half
     * @param applyPass   the apply half
     * @param boundRows   how bound rows are written and read
     * @param sniffer     what the content actually is
     * @param audit       the platform's one audit sink
     * @param actors      who is doing this
     * @param correlation the correlation id of the current request
     * @param scanner     the deployment's scanner, or null when it has none
     * @param authorities how an action's declared authority is checked, or null when none is declared
     * @param reports     how a reject report is produced and uploaded
     * @param clock       the clock every timestamp comes from
     */
    // SUPPRESS CHECKSTYLE ParameterNumber - a service that orchestrates eight collaborators has eight
    // dependencies. Grouping them into a holder would hide which of them a given path uses, which is the one
    // thing a reader of this class wants to know.
    @SuppressWarnings("checkstyle:ParameterNumber")
    public FileActionService(FileActionRegistry registry, FileActionSettings settings,
                            ObjectStore objectStore, FileActionSubmissionRepository submissions,
                            FileActionRowRejectRepository rejects, TemplateWriter templates,
                            SubmissionStore store, BindingPass bindingPass, ApplyPass applyPass,
                            BoundRowStore boundRows, FormatSniffer sniffer, AuditSink audit,
                            ActorResolver actors, CorrelationProvider correlation,
                            FileScanner scanner, AuthorityChecker authorities,
                            ErrorReportPublisher reports, Clock clock) {
        this.registry = registry;
        this.settings = settings;
        this.objectStore = objectStore;
        this.submissions = submissions;
        this.rejects = rejects;
        this.templates = templates;
        this.store = store;
        this.bindingPass = bindingPass;
        this.applyPass = applyPass;
        this.boundRows = boundRows;
        this.sniffer = sniffer;
        this.audit = audit;
        this.actors = actors;
        this.correlation = correlation;
        this.scanner = scanner;
        this.authorities = authorities;
        this.reports = reports;
        this.clock = clock;
    }

    /**
     * Refuses the request when the action declares an authority the caller does not hold.
     *
     * <p>Called by every entry point, including the reads. A submission's envelope carries its filename, its row
     * counts and its failure reason, and the rejects resource carries the contents of the user's own cells - so
     * "who may poll this" is the same question as "who may submit it", and answering it only on the write path
     * would make the authority a formality.
     *
     * <p>This is the enforcement half of {@code required-authority}. The property was configured, validated at
     * startup, documented in the README and named in the spec before this method existed, and nothing checked it -
     * which is the worst shape a security control can take, because it looks present.
     *
     * @param action the resolved action
     */
    private void authorise(ResolvedAction<?> action) {
        String required = action.requiredAuthority();
        if (required == null) {
            return;
        }
        if (authorities == null) {
            throw new IllegalStateException("action '" + action.name() + "' declares required-authority '"
                    + required + "' but no AuthorityChecker is available. The configuration validator refuses"
                    + " this combination at startup, so reaching here means it was bypassed");
        }
        if (!authorities.holds(required)) {
            throw new FileActionForbiddenException(action.name(), required);
        }
    }

    /**
     * Accepts a submitted file.
     *
     * @param actionName  the action from the path
     * @param body        how to open the request body. A supplier rather than a stream so that the checked
     *                    exception is dealt with here, where every other read in this module deals with one,
     *                    rather than in a controller
     * @param filename    the name the client sent
     * @param contentType the content type the client declared, which is a hint only
     * @return the submission, in whatever state this request left it
     */
    public SubmissionSnapshot submit(String actionName, ContentSource body, String filename,
                                     String contentType) {
        ResolvedAction<?> action = registry.require(actionName);
        authorise(action);
        Actor actor = actors.currentActorOrSystem();
        Path workDir = workDirectory();

        try (InputStream content = body.open();
                UploadSpool spool = UploadSpool.of(content, action.maxBytes(), workDir)) {
            Optional<FileActionSubmissionEntity> existing =
                    submissions.findByContent(actionName, spool.sha256(), actor.subject());
            if (existing.isPresent()) {
                // The normal case, not an edge case: a user double-clicked, or a browser retried a POST whose
                // response was lost. Answering with the first submission's envelope is what stops the orders
                // being created twice.
                record(FileActionAuditActions.DUPLICATE, action, existing.get(), actor, spool, null, true,
                        null);
                return SubmissionSnapshot.of(existing.get());
            }
            SourceFormat format = sniff(spool, filename, action);
            String objectUri = storeBytes(action, spool, filename, contentType, format);
            FileActionSubmissionEntity submission = createSubmission(action, spool, filename,
                    contentType, format, actor, objectUri);
            record(FileActionAuditActions.SUBMITTED, action, submission, actor, spool, null, true, null);

            if (!scan(action, submission, actor, spool, objectUri, filename)) {
                return SubmissionSnapshot.of(reload(submission.getId()));
            }
            if (action.execution() == ExecutionMode.DEFERRED) {
                // Accepted and nothing more. The worker claims it under a lease, so this request does not hold
                // a connection for the read and a pod dying does not lose the submission.
                return SubmissionSnapshot.of(submission);
            }
            return SubmissionSnapshot.of(process(action, submission, spool.file(), format, workDir, false));
        } catch (IOException failed) {
            throw new UncheckedIOException("the submission could not be accepted", failed);
        } finally {
            deleteQuietly(workDir);
        }
    }

    /**
     * Applies a validated submission that somebody has confirmed.
     *
     * @param actionName the action from the path
     * @param id         the submission
     * @return the submission, now applied or refused
     */
    public SubmissionSnapshot confirm(String actionName, UUID id) {
        ResolvedAction<?> action = registry.require(actionName);
        authorise(action);
        FileActionSubmissionEntity submission = require(actionName, id);
        if (!action.mode().applies()) {
            throw new SubmissionStateException(FileActionProblemCodes.NOT_CONFIRMABLE,
                    submission.getState());
        }
        if (submission.getState() == FileActionState.EXPIRED) {
            throw new SubmissionStateException(FileActionProblemCodes.CONFIRM_WINDOW_CLOSED,
                    submission.getState());
        }
        if (submission.getState() != FileActionState.VALIDATED) {
            throw new SubmissionStateException(FileActionProblemCodes.WRONG_STATE, submission.getState());
        }
        if (submission.getExpiresAt() != null && submission.getExpiresAt().isBefore(clock.instant())) {
            store.markExpired(submission.getId());
            throw new SubmissionStateException(FileActionProblemCodes.CONFIRM_WINDOW_CLOSED,
                    FileActionState.EXPIRED);
        }
        Actor actor = actors.currentActorOrSystem();
        record(FileActionAuditActions.CONFIRMED, action, submission, actor, null, null, true, null);
        return SubmissionSnapshot.of(applyFromArtifact(action, submission));
    }

    /**
     * Asks a running submission to stop.
     *
     * <p>Sets a flag the apply checks between batches. A submission that has already finished is returned as it
     * is rather than refused: the caller's intent - make sure this is not running - is already satisfied, and
     * the operation contract says a cancel of a terminal operation returns the envelope rather than a 409.
     *
     * @param actionName the action from the path
     * @param id         the submission
     * @return the submission
     */
    public SubmissionSnapshot cancel(String actionName, UUID id) {
        ResolvedAction<?> action = registry.require(actionName);
        authorise(action);
        FileActionSubmissionEntity submission = require(actionName, id);
        if (submission.isTerminal()) {
            return SubmissionSnapshot.of(submission);
        }
        FileActionSubmissionEntity cancelled = store.requestCancellation(submission.getId());
        record(FileActionAuditActions.CANCELLATION_REQUESTED, action, cancelled,
                actors.currentActorOrSystem(), null, null, true, null);
        return SubmissionSnapshot.of(cancelled);
    }

    /**
     * A submission, for a poll.
     *
     * @param actionName the action from the path
     * @param id         the submission
     * @return the submission
     */
    public SubmissionSnapshot find(String actionName, UUID id) {
        authorise(registry.require(actionName));
        return SubmissionSnapshot.of(require(actionName, id));
    }

    /**
     * A page of a submission's refused rows, already localised.
     *
     * <p>Here rather than on the controller because a controller that injected a repository would be reaching
     * past the engine into persistence - which the shared {@code web.controllers-do-not-use-persistence-types}
     * rule forbids, and rightly: the engine is where "which submission's rejects may this caller see" belongs.
     *
     * @param actionName the action from the path
     * @param id         the submission
     * @param pageable   the page
     * @return the page of rejects
     */
    public org.springframework.data.domain.Page<RejectView> rejects(String actionName, UUID id,
            org.springframework.data.domain.Pageable pageable) {
        authorise(registry.require(actionName));
        FileActionSubmissionEntity submission = require(actionName, id);
        return rejects.findBySubmission(submission.getId(), pageable).map(RejectView::of);
    }

    /**
     * How many of a submission's rejects are stored.
     *
     * @param id the submission
     * @return the stored count
     */
    public long rejectsStored(UUID id) {
        return rejects.countBySubmission(id);
    }

    /**
     * The blank template for an action.
     *
     * <p>Generated here rather than in the controller so that nothing in the web layer catches an
     * {@code IOException} - which {@code exceptions.controllers-do-not-catch-checked-exceptions} forbids, and
     * rightly: a controller that catches one has to decide what it means, and the decision belongs where the
     * work is.
     *
     * @param actionName the action from the path
     * @return the workbook
     */
    public byte[] template(String actionName) {
        ResolvedAction<?> action = registry.require(actionName);
        authorise(action);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try {
            // Buffered, unlike everything else, and deliberately: a template is one row of headings, its size is
            // bounded by the binding rather than by anything a user supplies, and streaming it would need a temp
            // file for no benefit.
            templates.write(action.binding(), bytes);
        } catch (IOException failed) {
            throw new UncheckedIOException("the template for action '" + actionName
                    + "' could not be generated", failed);
        }
        return bytes.toByteArray();
    }

    /**
     * Reads, binds and - unless the action waits for a confirmation - applies.
     *
     * <p>Called by the submit request for an {@code INLINE} action and by the worker for a {@code DEFERRED}
     * one, which is what keeps the two execution modes one code path rather than two.
     *
     * @param action     the resolved action
     * @param submission the submission row
     * @param content    a local file holding the bytes
     * @param format     the sniffed format
     * @param workDir    where artifacts are written
     * @param deferred   whether this is the worker rather than the request
     * @param <R>        the row type
     * @return the submission in its new state
     */
    public <R> FileActionSubmissionEntity process(ResolvedAction<R> action,
                                                  FileActionSubmissionEntity submission, Path content,
                                                  SourceFormat format, Path workDir, boolean deferred) {
        Actor actor = actors.currentActorOrSystem();
        store.transition(submission.getId(), FileActionState.APPLYING);
        CoercionContext coercion = coercionFor(submission);
        BindOutcome bound;
        try {
            bound = bindingPass.bind(action, content, format, coercion, workDir);
        } catch (FileRejectedException refused) {
            // A file-level refusal: too large, wrong format, a missing required column, an unreadable archive.
            // The submission is REJECTED with the code, which is what the envelope's failure carries.
            store.complete(submission.getId(), FileActionState.REJECTED, SubmissionStore.RowCounts.none(),
                    refused.getCode(), null);
            record(FileActionAuditActions.REJECTED, action, submission, actor, null, null, false,
                    refused.getCode());
            throw refused;
        }
        try {
            return afterBinding(action, submission, content, format, bound, coercion, actor, deferred);
        } finally {
            deleteQuietly(bound.boundRows());
        }
    }

    private <R> FileActionSubmissionEntity afterBinding(ResolvedAction<R> action,
                                                        FileActionSubmissionEntity submission, Path content,
                                                        SourceFormat format, BindOutcome bound,
                                                        CoercionContext coercion, Actor actor,
                                                        boolean deferred) {
        RejectCollector collected = bound.rejects();
        if (bound.exceedsThreshold(action)) {
            // The "wrong file entirely" case. Nothing is applied, whatever the commit policy, because applying
            // the sixty per cent that parsed is never what anybody wanted.
            store.complete(submission.getId(), FileActionState.REJECTED, counts(bound, 0),
                    FileActionProblemCodes.TOO_MANY_REJECTS, null);
            store.replaceRejects(reload(submission.getId()), collected.sample());
            store.recordArtifacts(submission.getId(), null,
                    reports.publish(action, submission, content, format, bound, coercion),
                    retentionFrom(action));
            record(FileActionAuditActions.REJECTED, action, submission, actor, null, bound, false,
                    FileActionProblemCodes.TOO_MANY_REJECTS);
            return reload(submission.getId());
        }

        if (action.mode() == ActionMode.VALIDATE_ONLY || action.mode().requiresConfirmation()) {
            String boundUri = uploadBoundRows(action, submission, bound);
            Instant expires = action.mode().requiresConfirmation()
                    ? clock.instant().plus(action.confirmTtl()) : retentionFrom(action);
            FileActionState next = action.mode() == ActionMode.VALIDATE_ONLY
                    ? FileActionState.APPLIED : FileActionState.VALIDATED;
            store.complete(submission.getId(), next, counts(bound, 0), null, null);
            store.replaceRejects(reload(submission.getId()), collected.sample());
            store.recordArtifacts(submission.getId(), boundUri,
                    reports.publish(action, submission, content, format, bound, coercion), expires);
            record(FileActionAuditActions.VALIDATED, action, submission, actor, null, bound, true, null);
            return reload(submission.getId());
        }

        ApplyOutcome outcome = applyPass.apply(action, bound.boundRows(), contextFor(action, submission,
                        coercion, false), collected,
                () -> submissions.isCancellationRequested(submission.getId()));
        // Persisted and reported AFTER the apply, not before it. A handler's reject is produced during the apply,
        // so storing the rejects first left it counted and invisible: the caller saw "1 rejected" with nothing in
        // the paged resource and nothing in the downloadable report. DeferredClaimIT.theRejectIsAddressed caught it.
        store.replaceRejects(reload(submission.getId()), collected.sample());
        return finish(action, submission, bound, outcome,
                reports.publish(action, submission, content, format, bound, coercion), actor);
    }

    private <R> FileActionSubmissionEntity applyFromArtifact(ResolvedAction<R> action,
                                                             FileActionSubmissionEntity submission) {
        Path workDir = workDirectory();
        try {
            Path local = Files.createTempFile(workDir, "ludwig-file-action-bound-", ".ndjson");
            try (InputStream stored = objectStore.open(submission.getBoundRowsUri())) {
                Files.copy(stored, local, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            store.transition(submission.getId(), FileActionState.APPLYING);
            CoercionContext coercion = coercionFor(submission);
            RejectCollector collected = new RejectCollector(action.rejectSample());
            ApplyOutcome outcome = applyPass.apply(action, local,
                    contextFor(action, submission, coercion, false), collected,
                    () -> submissions.isCancellationRequested(submission.getId()));
            SubmissionStore.RowCounts counts = new SubmissionStore.RowCounts(submission.getRowsRead(),
                    outcome.rowsApplied(), submission.getRowsRejected() + collected.rejectedRows(),
                    submission.getRowsSkipped() + collected.skippedRows());
            // Added, not replaced: the cell-level rejects the user was shown at validation are already stored, and
            // replacing them here would delete the detail the confirmation was approved against.
            store.addRejects(reload(submission.getId()), collected.sample());
            FileActionState state = stateFor(outcome);
            store.complete(submission.getId(), state, counts, failureCodeFor(outcome), null);
            record(FileActionAuditActions.APPLIED, action, submission, actors.currentActorOrSystem(), null,
                    null, state == FileActionState.APPLIED, failureCodeFor(outcome));
            return reload(submission.getId());
        } catch (IOException failed) {
            throw new UncheckedIOException("the bound rows could not be read back for confirmation", failed);
        } finally {
            deleteQuietly(workDir);
        }
    }

    private FileActionSubmissionEntity finish(ResolvedAction<?> action,
                                              FileActionSubmissionEntity submission, BindOutcome bound,
                                              ApplyOutcome outcome, String reportUri, Actor actor) {
        FileActionState state = stateFor(outcome);
        store.complete(submission.getId(), state, counts(bound, outcome.rowsApplied()),
                failureCodeFor(outcome), null);
        store.recordArtifacts(submission.getId(), null, reportUri, retentionFrom(action));
        record(FileActionAuditActions.APPLIED, action, submission, actor, null, bound,
                state == FileActionState.APPLIED, failureCodeFor(outcome));
        return reload(submission.getId());
    }

    private static FileActionState stateFor(ApplyOutcome outcome) {
        if (outcome.documentCode() != null) {
            return FileActionState.REJECTED;
        }
        if (outcome.hadFailedBatch() && outcome.rowsApplied() == 0) {
            return FileActionState.REJECTED;
        }
        // A submission that applied some rows and rejected others is APPLIED with a reject count: from the
        // caller's point of view the action happened, and reporting it as a failure would make every client
        // treat a partial import as an error.
        return FileActionState.APPLIED;
    }

    private static String failureCodeFor(ApplyOutcome outcome) {
        if (outcome.documentCode() != null) {
            return outcome.documentCode();
        }
        return outcome.hadFailedBatch() && outcome.rowsApplied() == 0
                ? FileActionProblemCodes.CONSTRAINT_VIOLATED : null;
    }

    private static SubmissionStore.RowCounts counts(BindOutcome bound, long applied) {
        return new SubmissionStore.RowCounts(bound.rowsRead(), applied,
                bound.rejects().rejectedRows(), bound.rejects().skippedRows());
    }

    private SourceFormat sniff(UploadSpool spool, String filename, ResolvedAction<?> action)
            throws IOException {
        try (InputStream content = FormatSniffer.sniffing(Files.newInputStream(spool.file()))) {
            SourceFormat format = sniffer.sniff(content, filename);
            if (!action.formats().contains(format)) {
                throw new FileRejectedException(ProblemStatus.UNSUPPORTED_MEDIA_TYPE,
                        FileActionProblemCodes.UNSUPPORTED_FORMAT,
                        String.join(", ", action.acceptedFormatNames()));
            }
            return format;
        }
    }

    private String storeBytes(ResolvedAction<?> action, UploadSpool spool, String filename,
                              String contentType, SourceFormat format) {
        String uri = ObjectKeys.upload(settings.uploadsPrefix(), clock.instant(),
                spool.sha256(), format);
        objectStore.put(uri, spool.file(), PutOptions.ofContentType(
                contentType == null ? format.mediaType() : contentType));
        LOG.debug("stored submission for action {} as {}", action.name(), uri);
        return uri;
    }

    private String uploadBoundRows(ResolvedAction<?> action, FileActionSubmissionEntity submission,
                                   BindOutcome bound) {
        String uri = ObjectKeys.boundRows(settings.artifactsPrefix(), submission.getId());
        objectStore.put(uri, bound.boundRows(), PutOptions.ofContentType("application/x-ndjson"));
        return uri;
    }

    private FileActionSubmissionEntity createSubmission(ResolvedAction<?> action, UploadSpool spool,
                                                        String filename, String contentType,
                                                        SourceFormat format, Actor actor,
                                                        String objectUri) {
        UserPreferences preferences = UserPreferences.current();
        return store.create(FileActionSubmissionEntity.builder()
                .action(action.name())
                .state(FileActionState.UPLOADED)
                .objectUri(objectUri)
                .contentSha256(spool.sha256())
                .sizeBytes(spool.sizeBytes())
                .declaredFilename(filename)
                .declaredContentType(contentType)
                .sourceFormat(format)
                .submittedBy(actor.subject())
                .submittedAt(clock.instant())
                .locale(preferences.locale().toLanguageTag())
                .zone(preferences.zone().getId())
                .correlationId(correlation.currentCorrelationId().orElse(null))
                .build());
    }

    private boolean scan(ResolvedAction<?> action, FileActionSubmissionEntity submission, Actor actor,
                         UploadSpool spool, String objectUri, String filename) throws IOException {
        ScanningMode mode = settings.scanningMode();
        if (mode == ScanningMode.DISABLED || scanner == null) {
            return true;
        }
        FileScanner configured = scanner;
        ScanOutcome outcome;
        try (InputStream content = Files.newInputStream(spool.file())) {
            outcome = configured.scan(content, filename, spool.sizeBytes());
        }
        record(FileActionAuditActions.SCANNED, action, submission, actor, spool, null, outcome.safe(),
                outcome.signature());
        if (outcome.safe()) {
            return true;
        }
        // Deleted, not kept. A file the scanner refused is one nobody should be able to fetch again, and
        // keeping it so that an operator could look at it would mean a bucket of known-bad files with the same
        // access rules as the good ones.
        objectStore.delete(objectUri);
        store.complete(submission.getId(), FileActionState.REJECTED, SubmissionStore.RowCounts.none(),
                FileActionProblemCodes.SCAN_REJECTED, null);
        throw new FileRejectedException(ProblemStatus.INVALID, FileActionProblemCodes.SCAN_REJECTED,
                filename);
    }

    private CoercionContext coercionFor(FileActionSubmissionEntity submission) {
        // The preferences recorded at submission, not the current thread's. A deferred submission is read on a
        // worker with no request bound to it, and a confirmation may come from a different person - so a date
        // must coerce as the person who uploaded the file meant it, which is what these two columns are for.
        Locale locale = submission.getLocale() == null
                ? UserPreferences.FALLBACK.locale() : Locale.forLanguageTag(submission.getLocale());
        ZoneId zone = submission.getZone() == null
                ? UserPreferences.FALLBACK.zone() : ZoneId.of(submission.getZone());
        return new CoercionContext(new UserPreferences(locale, zone), false);
    }

    private FileActionContext contextFor(ResolvedAction<?> action, FileActionSubmissionEntity submission,
                                         CoercionContext coercion, boolean dryRun) {
        return new FileActionContext(submission.getId(), action.name(), submission.getDeclaredFilename(),
                submission.getCorrelationId(), coercion.preferences(), dryRun);
    }

    private Instant retentionFrom(ResolvedAction<?> action) {
        return clock.instant().plus(settings.retention());
    }

    /**
     * Re-reads a submission after a state transition committed.
     *
     * <p>A real load, not {@code getReferenceById}. That method returns an <em>uninitialised proxy</em>, and this
     * service runs outside any transaction - the transitions open their own - so touching a field on the proxy
     * throws {@code LazyInitializationException}. Every integration test in this module failed that way before this
     * method existed, which is the same hazard {@link SubmissionSnapshot} is documented against: the proxy is fine
     * inside {@code SubmissionStore}, where a transaction is open, and wrong everywhere else.
     *
     * @param id the submission
     * @return the row, loaded
     */
    private FileActionSubmissionEntity reload(UUID id) {
        return submissions.findById(id).orElseThrow(() -> new IllegalStateException(
                "submission " + id + " was written by this request and is no longer there"));
    }

    private FileActionSubmissionEntity require(String actionName, UUID id) {
        return submissions.findByActionAndId(actionName, id)
                .orElseThrow(() -> new SubmissionNotFoundException(actionName, id));
    }

    // SUPPRESS CHECKSTYLE ParameterNumber - one audit call site shape rather than nine overloads, so that
    // every event from this module is assembled the same way.
    @SuppressWarnings("checkstyle:ParameterNumber")
    private void record(String auditAction, ResolvedAction<?> action,
                        FileActionSubmissionEntity submission, Actor actor, UploadSpool spool,
                        BindOutcome bound, boolean succeeded, String reasonCode) {
        // No try/catch. Whether a sink failure fails the caller is AuditFailurePolicy, resolved from
        // configuration, and a catch in a library would override a deployment's decision - which is why
        // CLAUDE.md forbids one here.
        audit.record(FileActionAuditEvent.builder()
                .action(action.name())
                .submissionId(submission == null ? null : submission.getId())
                .auditAction(auditAction)
                .actor(actor)
                .filename(submission == null ? null : submission.getDeclaredFilename())
                .contentSha256(spool == null
                        ? (submission == null ? null : submission.getContentSha256()) : spool.sha256())
                .sizeBytes(spool == null
                        ? (submission == null ? null : submission.getSizeBytes()) : spool.sizeBytes())
                .rowsRead(bound == null ? null : bound.rowsRead())
                .rowsRejected(bound == null ? null : bound.rejects().rejectedRows())
                .scanner(scanner == null ? null : scanner.name())
                .reasonCode(reasonCode)
                .correlationId(correlation.currentCorrelationId().orElse(null))
                .occurredAt(clock.instant())
                .succeeded(succeeded)
                .build()
                .toAuditEvent());
    }

    private Path workDirectory() {
        try {
            return Files.createTempDirectory("ludwig-file-action-");
        } catch (IOException failed) {
            throw new UncheckedIOException("a working directory could not be created", failed);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (java.util.stream.Stream<Path> entries = Files.list(path)) {
                    entries.forEach(FileActionService::deleteQuietly);
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException leftBehind) {
            LOG.warn("could not clean up {}", path, leftBehind);
        }
    }
}
