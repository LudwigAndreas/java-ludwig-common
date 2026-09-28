package ru.ludwigandreas.notification.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.util.UriComponentsBuilder;
import ru.ludwigandreas.notification.service.NotificationService;
import ru.ludwigandreas.notification.service.model.CategoryClass;
import ru.ludwigandreas.notification.service.model.NotificationCommand;
import ru.ludwigandreas.notification.service.model.NotificationRequestView;
import ru.ludwigandreas.notification.service.model.Priority;
import ru.ludwigandreas.notification.service.model.RequestState;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.web.NotificationController;
import ru.ludwigandreas.notification.web.dto.CategoryClassDto;
import ru.ludwigandreas.notification.web.dto.ChannelTypeDto;
import ru.ludwigandreas.notification.web.dto.NotificationRequestResponse;
import ru.ludwigandreas.notification.web.dto.PriorityDto;
import ru.ludwigandreas.notification.web.dto.RecipientDto;
import ru.ludwigandreas.notification.web.dto.SendNotificationRequest;
import ru.ludwigandreas.notification.web.mapper.NotificationDtoMapperImpl;
import ru.ludwigandreas.observability.correlation.CorrelationContext;
import ru.ludwigandreas.webcore.operation.OperationHeaders;
import ru.ludwigandreas.webcore.operation.OperationStatus;
import ru.ludwigandreas.webcore.problem.ProblemDetailFactory;
import ru.ludwigandreas.webcore.problem.ProblemMapperRegistry;

/**
 * This service's requests in the platform's long-running-operation envelope.
 *
 * <p>Two claims, and the second is the one that will be got wrong later: the envelope's state is the
 * <em>request's</em> state and not its deliveries', and a request that has not fanned out yet answers
 * with a {@code Retry-After}.
 */
class NotificationOperationEnvelopeTest {

    private static final String BASE_PATH = "/api/v1/notifications";

    private static final UUID REQUEST_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private final NotificationService service = mock(NotificationService.class);

    private final CorrelationContext correlation = mock(CorrelationContext.class);

    private final NotificationProperties properties = new NotificationProperties();

    private NotificationController controller;

    @BeforeEach
    void setUp() {
        when(correlation.currentId()).thenReturn(Optional.of("corr-1"));
        controller = new NotificationController(service, new NotificationDtoMapperImpl(), correlation,
                mock(ProblemDetailFactory.class), mock(ProblemMapperRegistry.class), properties);
    }

    @Test
    @DisplayName("a fresh submit answers 202 with a Location and a PENDING envelope")
    void submitCarriesLocationAndPendingEnvelope() {
        when(service.submit(any(NotificationCommand.class)))
                .thenReturn(view(RequestState.ACCEPTED, false));

        ResponseEntity<NotificationRequestResponse> response = controller.submit(
                request(), "key-1", UriComponentsBuilder.fromPath(""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION))
                .isEqualTo(BASE_PATH + "/" + REQUEST_ID);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().operation().status()).isEqualTo(OperationStatus.PENDING);
        // The service's own word is carried through rather than erased by the common core.
        assertThat(response.getBody().operation().detail()).isEqualTo("ACCEPTED");
        assertThat(response.getBody().state()).isEqualTo("ACCEPTED");
        assertThat(response.getBody().operation().correlationId()).isEqualTo("corr-1");
    }

    @Test
    @DisplayName("a fanned-out request is SUCCEEDED and points at itself as the result")
    void fannedOutIsSucceededWithAResult() {
        when(service.get(REQUEST_ID)).thenReturn(view(RequestState.FANNED_OUT, false));

        ResponseEntity<NotificationRequestResponse> response = controller.get(REQUEST_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(OperationHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().operation().status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(response.getBody().operation().result().href())
                .isEqualTo(BASE_PATH + "/" + REQUEST_ID);
    }

    @Test
    @DisplayName("reading a request that has not fanned out yet carries Retry-After")
    void pollOfAnAcceptedRequestCarriesRetryAfter() {
        properties.getIngress().getRest().setRetryAfter(Duration.ofSeconds(4));
        when(service.get(REQUEST_ID)).thenReturn(view(RequestState.ACCEPTED, false));

        ResponseEntity<NotificationRequestResponse> response = controller.get(REQUEST_ID);

        assertThat(response.getHeaders().getFirst(OperationHeaders.RETRY_AFTER)).isEqualTo("4");
    }

    @Test
    @DisplayName("a rejected request is FAILED with a code a client can branch on")
    void rejectedIsFailedWithACode() {
        when(service.get(REQUEST_ID)).thenReturn(view(RequestState.REJECTED, false));

        ResponseEntity<NotificationRequestResponse> response = controller.get(REQUEST_ID);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().operation().status()).isEqualTo(OperationStatus.FAILED);
        assertThat(response.getBody().operation().failure().code())
                .isEqualTo("error.notification.request.rejected");
        assertThat(response.getBody().operation().failure().detail()).isNotBlank();
        assertThat(response.getBody().operation().result()).isNull();
    }

    @Test
    @DisplayName("a duplicate answers 200, not 202: nothing new was started")
    void duplicateIsNotAnAcceptance() {
        when(service.submit(any(NotificationCommand.class)))
                .thenReturn(view(RequestState.FANNED_OUT, true));

        ResponseEntity<NotificationRequestResponse> response = controller.submit(
                request(), "key-1", UriComponentsBuilder.fromPath(""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().duplicate()).isTrue();
    }

    @Test
    @DisplayName("a duplicate of a request still fanning out is told when to look again")
    void duplicateOfInFlightWorkCarriesRetryAfter() {
        when(service.submit(any(NotificationCommand.class)))
                .thenReturn(view(RequestState.ACCEPTED, true));

        ResponseEntity<NotificationRequestResponse> response = controller.submit(
                request(), "key-1", UriComponentsBuilder.fromPath(""));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst(OperationHeaders.RETRY_AFTER)).isEqualTo("1");
    }

    private static SendNotificationRequest request() {
        return new SendNotificationRequest("password-reset", "security",
                CategoryClassDto.TRANSACTIONAL, PriorityDto.HIGH, Set.of(ChannelTypeDto.EMAIL),
                List.of(new RecipientDto("user-1", null, null, null, null)), Map.of(), null);
    }

    private static NotificationRequestView view(RequestState state, boolean duplicate) {
        return new NotificationRequestView(REQUEST_ID, "key-1", "password-reset", "security",
                CategoryClass.TRANSACTIONAL, Priority.HIGH, state, null,
                Instant.parse("2026-09-27T09:00:00Z"),
                state == RequestState.REJECTED
                        ? "no recipient resolved to a usable address on any requested channel"
                        : null,
                List.of(), duplicate);
    }
}
