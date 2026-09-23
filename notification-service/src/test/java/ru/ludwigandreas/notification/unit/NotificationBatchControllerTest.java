package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import ru.ludwigandreas.notification.service.NotificationService;
import ru.ludwigandreas.notification.service.exception.BatchTooLargeException;
import ru.ludwigandreas.notification.service.exception.TemplateNotFoundException;
import ru.ludwigandreas.notification.service.model.CategoryClass;
import ru.ludwigandreas.notification.service.model.NotificationCommand;
import ru.ludwigandreas.notification.service.model.NotificationRequestView;
import ru.ludwigandreas.notification.service.model.Priority;
import ru.ludwigandreas.notification.service.model.RequestState;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.webcore.problem.ProblemDetailFactory;
import ru.ludwigandreas.webcore.problem.ProblemMapperRegistry;
import ru.ludwigandreas.notification.web.NotificationController;
import ru.ludwigandreas.notification.web.dto.BatchSendRequest;
import ru.ludwigandreas.notification.web.dto.BatchSendResponse;
import ru.ludwigandreas.notification.web.dto.CategoryClassDto;
import ru.ludwigandreas.notification.web.dto.ChannelTypeDto;
import ru.ludwigandreas.notification.web.dto.PriorityDto;
import ru.ludwigandreas.notification.web.dto.RecipientDto;
import ru.ludwigandreas.notification.web.dto.SendNotificationRequest;
import ru.ludwigandreas.notification.web.mapper.NotificationDtoMapperImpl;
import ru.ludwigandreas.observability.correlation.CorrelationContext;

/**
 * The batch endpoint's isolation guarantee, which is the only thing about it that is not
 * bookkeeping.
 *
 * <p>Tested here rather than end to end on purpose: what has to be proved is that a submission
 * throwing does not take the rest of the batch with it, and nothing in the real submit path fails
 * deterministically at ingress - an unknown template, for instance, is accepted and discovered at
 * render time. Driving a stub that throws exactly where the test needs it to is the difference
 * between asserting the property and hoping to provoke it.
 */
class NotificationBatchControllerTest {

    private final NotificationService service = mock(NotificationService.class);
    private final ProblemDetailFactory problems = mock(ProblemDetailFactory.class);
    private final ProblemMapperRegistry problemMappers = mock(ProblemMapperRegistry.class);
    private final CorrelationContext correlation = mock(CorrelationContext.class);
    private final NotificationProperties properties = new NotificationProperties();

    private NotificationController controller;

    @BeforeEach
    void setUp() {
        when(correlation.currentId()).thenReturn(Optional.empty());
        // The real pipeline's two steps, stubbed at their seam: an unmapped exception falls through
        // to the generic definition, which is rendered like any other. What is under test here is the
        // loop's isolation, not the wording of a problem.
        when(problemMappers.resolve(any())).thenReturn(Optional.empty());
        when(problems.create(any(ru.ludwigandreas.webcore.problem.ProblemDefinition.class), anyString()))
                .thenReturn(ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        when(problems.create(any(ru.ludwigandreas.webcore.problem.LocalizedException.class), anyString()))
                .thenReturn(ProblemDetail.forStatus(HttpStatus.NOT_FOUND));
        controller = new NotificationController(service, new NotificationDtoMapperImpl(),
                correlation, problems, problemMappers, properties);
    }

    @Test
    @DisplayName("a failing item is reported and the rest of the batch still goes through")
    void oneFailureDoesNotDiscardTheRest() {
        when(service.submit(any(NotificationCommand.class)))
                .thenReturn(view())
                .thenThrow(new TemplateNotFoundException("no-such-template", "EMAIL", "en"))
                .thenReturn(view());

        BatchSendResponse response = submit(batch("a", "b", "c"));

        assertThat(response.accepted()).isEqualTo(2);
        assertThat(response.rejected()).isEqualTo(1);
        // Every item is reported, in order, whatever happened to it - a caller lines results up by
        // index or by its own reference and never by "the ones that came back".
        assertThat(response.results()).hasSize(3);
        assertThat(response.results().get(1).index()).isEqualTo(1);
        assertThat(response.results().get(1).reference()).isEqualTo("b");
        assertThat(response.results().get(1).isAccepted()).isFalse();
        assertThat(response.results().get(1).problem()).isNotNull();
        assertThat(response.results().get(2).isAccepted()).isTrue();
        // The third item was attempted, which is the whole point: the loop did not stop at the
        // failure.
        verify(service, times(3)).submit(any(NotificationCommand.class));
    }

    @Test
    @DisplayName("fail-fast stops at the first failure when a deployment asks it to")
    void failFastStopsAtTheFirstFailure() {
        properties.getIngress().getRest().setFailFast(true);
        when(service.submit(any(NotificationCommand.class)))
                .thenThrow(new TemplateNotFoundException("no-such-template", "EMAIL", "en"));

        BatchSendResponse response = submit(batch("a", "b", "c"));

        assertThat(response.accepted()).isZero();
        assertThat(response.rejected()).isEqualTo(1);
        verify(service, times(1)).submit(any(NotificationCommand.class));
    }

    @Test
    @DisplayName("a batch over the configured limit is refused before anything is submitted")
    void oversizedBatchIsRefusedBeforeAnyWork() {
        properties.getIngress().getRest().setMaxBatchSize(2);

        assertThatThrownBy(() -> submit(batch("a", "b", "c")))
                .isInstanceOf(BatchTooLargeException.class);

        Mockito.verifyNoInteractions(service);
    }

    @Test
    @DisplayName("each item carries its own idempotency key through to the command")
    void perItemIdempotencyKeys() {
        when(service.submit(any(NotificationCommand.class))).thenReturn(view());

        submit(batch("a", "b"));

        var captor = org.mockito.ArgumentCaptor.forClass(NotificationCommand.class);
        verify(service, times(2)).submit(captor.capture());
        assertThat(captor.getAllValues()).extracting(NotificationCommand::idempotencyKey)
                .containsExactly("key-a", "key-b");
    }

    private BatchSendResponse submit(BatchSendRequest batch) {
        return controller.submitBatch(batch).getBody();
    }

    private static BatchSendRequest batch(String... references) {
        return new BatchSendRequest(java.util.Arrays.stream(references)
                .map(reference -> new BatchSendRequest.BatchItem(
                        reference, "key-" + reference, request()))
                .toList());
    }

    private static SendNotificationRequest request() {
        return new SendNotificationRequest("password-reset", "security",
                CategoryClassDto.TRANSACTIONAL, PriorityDto.HIGH, Set.of(ChannelTypeDto.EMAIL),
                List.of(new RecipientDto("user-1", null, null, null, null)), Map.of(), null);
    }

    private static NotificationRequestView view() {
        return new NotificationRequestView(UUID.randomUUID(), "key", "password-reset", "security",
                CategoryClass.TRANSACTIONAL, Priority.HIGH, RequestState.FANNED_OUT, null,
                Instant.now(), null, List.of(), false);
    }
}
