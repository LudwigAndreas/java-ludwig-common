package ru.ludwigandreas.notification.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import ru.ludwigandreas.notification.repository.AnnouncementContentRepository;
import ru.ludwigandreas.notification.repository.AnnouncementMarkerRepository;
import ru.ludwigandreas.notification.repository.AnnouncementRepository;
import ru.ludwigandreas.notification.repository.entity.AnnouncementEntity;
import ru.ludwigandreas.notification.service.announcement.AnnouncementAdminService;
import ru.ludwigandreas.notification.service.model.AudienceType;

/**
 * Withdrawal and window changes as the endpoints answer them.
 *
 * <p>What each one audits is asserted in {@code AnnouncementLifecycleTest}, because this context's
 * sink cannot be substituted; the fan-out half of a withdrawal is in {@code AnnouncementEmailFanOutIT},
 * which already owns the users and the scheduler it needs.
 */
@AutoConfigureMockMvc
class AnnouncementLifecycleIT extends NotificationTestBase {

    private static final Map<String, Object> VARIABLES =
            Map.of("productName", "Ludwig", "version", "1.4.0");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AnnouncementAdminService adminService;

    @Autowired
    private AnnouncementRepository announcements;

    @Autowired
    private AnnouncementContentRepository contents;

    @Autowired
    private AnnouncementMarkerRepository markers;

    @BeforeEach
    void resetState() {
        markers.deleteAll();
        contents.deleteAll();
        announcements.deleteAll();
    }

    // ---------------------------------------------------------------------------------------------
    // Withdrawal
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an announcer withdraws an announcement and gets the withdrawal back")
    void withdrawalAnswersWithTheRecord() throws Exception {
        UUID id = publishOpen();

        mockMvc.perform(post("/api/v1/announcements/{id}/withdrawal", id)
                        .with(TestPrincipals.announcer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"wrong audience\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawnAt").isNotEmpty())
                .andExpect(jsonPath("$.withdrawnBy").value("announcer-subject"))
                .andExpect(jsonPath("$.withdrawalReason").value("wrong audience"));

        assertThat(announcements.findById(id)).isPresent();
    }

    @Test
    @DisplayName("a withdrawal needs no body")
    void withdrawalWithoutAReason() throws Exception {
        UUID id = publishOpen();

        mockMvc.perform(post("/api/v1/announcements/{id}/withdrawal", id)
                        .with(TestPrincipals.announcer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawalReason").doesNotExist());
    }

    @Test
    @DisplayName("withdrawing twice answers with the first withdrawal")
    void repeatWithdrawalIsIdempotent() throws Exception {
        UUID id = publishOpen();
        Instant first = adminService.withdraw(id, "first").withdrawnAt();

        mockMvc.perform(post("/api/v1/announcements/{id}/withdrawal", id)
                        .with(TestPrincipals.announcer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"second\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.withdrawalReason").value("first"));

        assertThat(announcements.findById(id).orElseThrow().getWithdrawnAt())
                .isCloseTo(first, within(1, ChronoUnit.MILLIS));
    }

    /** The notification admin is a different power, and a recipient none at all. */
    @Test
    @DisplayName("withdrawal without the announcer role is refused and changes nothing")
    void withdrawalNeedsTheAnnouncerRole() throws Exception {
        UUID id = publishOpen();

        mockMvc.perform(post("/api/v1/announcements/{id}/withdrawal", id)
                        .with(TestPrincipals.admin()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/announcements/{id}/withdrawal", id)
                        .with(TestPrincipals.recipient("lifecycle-recipient")))
                .andExpect(status().isForbidden());

        assertThat(announcements.findById(id).orElseThrow().getWithdrawnAt()).isNull();
    }

    @Test
    @DisplayName("withdrawing an unknown announcement is a 404")
    void unknownAnnouncementIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/announcements/{id}/withdrawal", UUID.randomUUID())
                        .with(TestPrincipals.announcer()))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------------------------------------
    // Window changes
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the end of an open window moves later")
    void endIsExtended() throws Exception {
        UUID id = publishOpen();
        Instant until = Instant.now().plus(20, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        changeWindow(id, null, until)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibleUntil").value(until.toString()));

        assertThat(announcements.findById(id).orElseThrow().getVisibleUntil()).isEqualTo(until);
    }

    @Test
    @DisplayName("the end of an open window is brought forward")
    void endIsBroughtForward() throws Exception {
        UUID id = publishOpen();
        Instant until = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        changeWindow(id, null, until).andExpect(status().isOk());

        assertThat(announcements.findById(id).orElseThrow().getVisibleUntil()).isEqualTo(until);
    }

    @Test
    @DisplayName("an end in the past is a 422 and leaves the window unchanged")
    void endInThePastIsUnprocessable() throws Exception {
        UUID id = publishOpen();
        Instant before = announcements.findById(id).orElseThrow().getVisibleUntil();

        changeWindow(id, null, Instant.now().minus(1, ChronoUnit.HOURS))
                .andExpect(status().isUnprocessableEntity());

        assertThat(announcements.findById(id).orElseThrow().getVisibleUntil()).isEqualTo(before);
    }

    @Test
    @DisplayName("moving the start of an open window is a 409")
    void startOfOpenWindowIsAConflict() throws Exception {
        UUID id = publishOpen();

        changeWindow(id, Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().plus(3, ChronoUnit.DAYS))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("the start of a window that has not opened moves")
    void startOfFutureWindowMoves() throws Exception {
        Instant now = Instant.now();
        UUID id = adminService.publish("platform-release", AudienceType.EVERYONE, null,
                        "platform-release", VARIABLES, now.plus(2, ChronoUnit.DAYS),
                        now.plus(5, ChronoUnit.DAYS))
                .announcement().id();
        Instant from = now.plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        changeWindow(id, from, now.plus(5, ChronoUnit.DAYS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibleFrom").value(from.toString()));
    }

    @Test
    @DisplayName("a window longer than the maximum is a 422 naming both lengths")
    void windowOverTheMaximumIsUnprocessable() throws Exception {
        UUID id = publishOpen();

        changeWindow(id, null, Instant.now().plus(3650, ChronoUnit.DAYS))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.requestedWindow").isNotEmpty())
                .andExpect(jsonPath("$.permittedWindow").isNotEmpty());
    }

    @Test
    @DisplayName("a window change without an end is a 400")
    void windowChangeNeedsAnEnd() throws Exception {
        UUID id = publishOpen();

        mockMvc.perform(put("/api/v1/announcements/{id}/window", id)
                        .with(TestPrincipals.announcer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a window change without the announcer role is refused")
    void windowChangeNeedsTheAnnouncerRole() throws Exception {
        UUID id = publishOpen();

        mockMvc.perform(put("/api/v1/announcements/{id}/window", id)
                        .with(TestPrincipals.admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(windowBody(null, Instant.now().plus(2, ChronoUnit.DAYS))))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------------------------------
    // Finality and concurrency
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a withdrawn announcement's window and content are a 409")
    void withdrawnIsFinal() throws Exception {
        UUID id = publishOpen();
        adminService.withdraw(id, null);
        Instant until = announcements.findById(id).orElseThrow().getVisibleUntil();

        changeWindow(id, null, Instant.now().plus(5, ChronoUnit.DAYS))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/v1/announcements/{id}/corrections", id)
                        .with(TestPrincipals.announcer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\":{\"productName\":\"Ludwig\",\"version\":\"1.4.1\"}}"))
                .andExpect(status().isConflict());

        assertThat(announcements.findById(id).orElseThrow().getVisibleUntil()).isEqualTo(until);
    }

    /** Two announcers working from the same version: the second write fails rather than overwriting. */
    @Test
    @DisplayName("a change based on a stale version is refused, not silently applied")
    void staleWriteFailsOnTheVersion() {
        UUID id = publishOpen();
        AnnouncementEntity stale = announcements.findById(id).orElseThrow();

        adminService.withdraw(id, "first");
        stale.setVisibleUntil(Instant.now().plus(10, ChronoUnit.DAYS));

        assertThatThrownBy(() -> announcements.save(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    private ResultActions changeWindow(UUID id, Instant from, Instant until) throws Exception {
        return mockMvc.perform(put("/api/v1/announcements/{id}/window", id)
                .with(TestPrincipals.announcer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(windowBody(from, until)));
    }

    private static String windowBody(Instant from, Instant until) {
        return from == null
                ? "{\"visibleUntil\":\"" + until + "\"}"
                : "{\"visibleFrom\":\"" + from + "\",\"visibleUntil\":\"" + until + "\"}";
    }

    private UUID publishOpen() {
        Instant from = Instant.now().minusSeconds(1);
        return adminService.publish("platform-release", AudienceType.EVERYONE, null,
                        "platform-release", VARIABLES, from, from.plus(7, ChronoUnit.DAYS))
                .announcement().id();
    }
}
