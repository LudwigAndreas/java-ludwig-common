package ru.ludwigandreas.fileaction.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import ru.ludwigandreas.audit.ActorResolver;
import ru.ludwigandreas.audit.AuditSink;
import ru.ludwigandreas.audit.CorrelationProvider;
import ru.ludwigandreas.fileaction.api.CommitPolicy;
import ru.ludwigandreas.fileaction.api.FileActionContext;
import ru.ludwigandreas.fileaction.api.RowBinding;
import ru.ludwigandreas.fileaction.api.RowHandler;
import ru.ludwigandreas.fileaction.api.RowOutcome;
import ru.ludwigandreas.fileaction.api.ScanningMode;
import ru.ludwigandreas.fileaction.engine.ApplyPass;
import ru.ludwigandreas.fileaction.engine.AuthorityChecker;
import ru.ludwigandreas.fileaction.engine.BindingPass;
import ru.ludwigandreas.fileaction.engine.BoundRowStore;
import ru.ludwigandreas.fileaction.engine.ErrorReportPublisher;
import ru.ludwigandreas.fileaction.engine.FileActionRegistry;
import ru.ludwigandreas.fileaction.engine.FileActionService;
import ru.ludwigandreas.fileaction.engine.FileActionSettings;
import ru.ludwigandreas.fileaction.engine.ResolvedAction;
import ru.ludwigandreas.fileaction.engine.SubmissionStore;
import ru.ludwigandreas.fileaction.exception.FileActionForbiddenException;
import ru.ludwigandreas.fileaction.format.FormatSniffer;
import ru.ludwigandreas.fileaction.format.xlsx.write.TemplateWriter;
import ru.ludwigandreas.fileaction.repository.FileActionRowRejectRepository;
import ru.ludwigandreas.fileaction.repository.FileActionSubmissionRepository;
import ru.ludwigandreas.storage.api.ObjectStore;

/**
 * An action's declared authority is actually checked, on every entry point.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>Because for a while it did not, and the control was not enforced. {@code required-authority} was a configured
 * property, validated at startup, documented in the README and named in the capability spec - and nothing ever
 * read it. That is the worst shape a security control can take, because every artefact around it says it is there.
 *
 * <p>So this asserts the enforcement rather than the configuration, and it asserts it on the <em>reads</em> as
 * well. A submission's envelope carries its filename, its row counts and its failure reason, and the rejects
 * resource carries the contents of the user's own cells - so "who may poll this" is the same question as "who may
 * submit it", and checking only the write path would make the authority a formality.
 */
class AuthorityEnforcementTest {

    private static final String AUTHORITY = "ORDER_IMPORT";
    private static final UUID SUBMISSION = UUID.randomUUID();

    private FileActionSubmissionRepository submissions;
    private AuthorityChecker checker;

    @BeforeEach
    void setUp() {
        submissions = mock(FileActionSubmissionRepository.class);
        checker = mock(AuthorityChecker.class);
        when(submissions.findByActionAndId(any(), any())).thenReturn(Optional.empty());
        when(submissions.findByContent(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("submit is refused when the caller does not hold the authority")
    void submitIsRefused() {
        FileActionService service = service(AUTHORITY);
        when(checker.holds(AUTHORITY)).thenReturn(false);

        assertThatThrownBy(() -> service.submit("order-import",
                () -> new ByteArrayInputStream("SKU,Qty\n".getBytes()), "orders.csv", "text/csv"))
                .isInstanceOf(FileActionForbiddenException.class);
    }

    @Test
    @DisplayName("nothing is stored when the caller is refused, so the check is before the work")
    void nothingIsStoredWhenRefused() {
        ObjectStore store = mock(ObjectStore.class);
        FileActionService service = service(AUTHORITY, store);
        when(checker.holds(AUTHORITY)).thenReturn(false);

        assertThatThrownBy(() -> service.submit("order-import",
                () -> new ByteArrayInputStream("SKU,Qty\n".getBytes()), "orders.csv", "text/csv"))
                .isInstanceOf(FileActionForbiddenException.class);

        verify(store, never()).put(any(), any(), any());
    }

    @Test
    @DisplayName("polling is refused too, because the envelope carries the filename and the counts")
    void pollingIsRefused() {
        FileActionService service = service(AUTHORITY);
        when(checker.holds(AUTHORITY)).thenReturn(false);

        assertThatThrownBy(() -> service.find("order-import", SUBMISSION))
                .isInstanceOf(FileActionForbiddenException.class);
    }

    @Test
    @DisplayName("the rejects resource is refused, because it carries the contents of the user's own cells")
    void rejectsAreRefused() {
        FileActionService service = service(AUTHORITY);
        when(checker.holds(AUTHORITY)).thenReturn(false);

        assertThatThrownBy(() -> service.rejects("order-import", SUBMISSION, Pageable.ofSize(10)))
                .isInstanceOf(FileActionForbiddenException.class);
    }

    @Test
    @DisplayName("confirm and cancel are refused")
    void confirmAndCancelAreRefused() {
        FileActionService service = service(AUTHORITY);
        when(checker.holds(AUTHORITY)).thenReturn(false);

        assertThatThrownBy(() -> service.confirm("order-import", SUBMISSION))
                .isInstanceOf(FileActionForbiddenException.class);
        assertThatThrownBy(() -> service.cancel("order-import", SUBMISSION))
                .isInstanceOf(FileActionForbiddenException.class);
    }

    @Test
    @DisplayName("the template is refused, because its headings describe the action's shape")
    void theTemplateIsRefused() {
        FileActionService service = service(AUTHORITY);
        when(checker.holds(AUTHORITY)).thenReturn(false);

        assertThatThrownBy(() -> service.template("order-import"))
                .isInstanceOf(FileActionForbiddenException.class);
    }

    @Test
    @DisplayName("a caller who holds the authority gets past the check")
    void aHolderGetsPast() {
        FileActionService service = service(AUTHORITY);
        when(checker.holds(AUTHORITY)).thenReturn(true);

        assertThatCode(() -> service.template("order-import")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an action declaring no authority checks nothing")
    void noAuthorityMeansNoCheck() {
        FileActionService service = service(null);

        assertThatCode(() -> service.template("order-import")).doesNotThrowAnyException();
        verify(checker, never()).holds(any());
    }

    @Test
    @DisplayName("a declared authority with no checker is a defect, reported rather than allowed through")
    void aMissingCheckerIsADefect() {
        FileActionService service = service(AUTHORITY, mock(ObjectStore.class), null);

        assertThatThrownBy(() -> service.template("order-import"))
                .as("the validator refuses this combination at startup, so reaching here means it was bypassed -"
                        + " and the safe answer is to fail, not to permit")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no AuthorityChecker is available");
    }

    @Test
    @DisplayName("the forbidden problem is a 403 naming the action and the authority")
    void theProblemNamesTheAuthority() {
        var problem = new FileActionForbiddenException("order-import", AUTHORITY).toDefinition();

        assertThat(problem.status())
                .isEqualTo(ru.ludwigandreas.webcore.problem.ProblemStatus.FORBIDDEN);
        assertThat(problem.args())
                .as("the person reading this is usually an administrator working out which role to grant")
                .containsExactly("order-import", AUTHORITY);
    }

    private FileActionService service(String authority) {
        return service(authority, mock(ObjectStore.class));
    }

    private FileActionService service(String authority, ObjectStore store) {
        return service(authority, store, checker);
    }

    // SUPPRESS CHECKSTYLE ParameterNumber - assembling the service under test with every collaborator mocked.
    @SuppressWarnings("checkstyle:ParameterNumber")
    private FileActionService service(String authority, ObjectStore store, AuthorityChecker authorities) {
        ResolvedAction<OrderLine> action = new ResolvedAction<>("order-import", new ApplyEverything(),
                TestActions.BINDING, new ru.ludwigandreas.fileaction.engine.RowMaterialiser<>(
                        TestActions.BINDING, null),
                ru.ludwigandreas.fileaction.api.ActionMode.DIRECT,
                ru.ludwigandreas.fileaction.api.ExecutionMode.INLINE, CommitPolicy.PER_ROW,
                java.util.Set.of(ru.ludwigandreas.fileaction.format.SourceFormat.CSV),
                1024L * 1024, 100, 100, 0.1d, Duration.ofMinutes(30),
                ru.ludwigandreas.fileaction.api.ErrorReportFormat.NONE, authority,
                ReadBudgets.generous());
        return new FileActionService(new FileActionRegistry(List.of(action)),
                new FileActionSettings("file:///tmp/u", "file:///tmp/a", Duration.ofDays(7),
                        ScanningMode.DISABLED),
                store, submissions, mock(FileActionRowRejectRepository.class), new TemplateWriter(),
                mock(SubmissionStore.class), mock(BindingPass.class), mock(ApplyPass.class),
                mock(BoundRowStore.class), new FormatSniffer(), mock(AuditSink.class),
                (ActorResolver) java.util.Optional::empty, CorrelationProvider.none(), null,
                authorities, mock(ErrorReportPublisher.class),
                Clock.fixed(Instant.parse("2026-02-01T12:00:00Z"), ZoneOffset.UTC));
    }

    /** A handler, so the resolved action has one. */
    private static final class ApplyEverything implements RowHandler<OrderLine> {
        @Override
        public RowBinding<OrderLine> binding() {
            return TestActions.BINDING;
        }

        @Override
        public RowOutcome apply(OrderLine row, FileActionContext context) {
            return RowOutcome.applied();
        }
    }
}
