package ru.ludwigandreas.notification.service;

import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.notification.repository.RecipientPreferenceRepository;
import ru.ludwigandreas.notification.repository.RecipientProfileRepository;
import ru.ludwigandreas.notification.repository.entity.ChannelKind;
import ru.ludwigandreas.notification.repository.entity.RecipientPreferenceEntity;
import ru.ludwigandreas.notification.repository.entity.RecipientProfileEntity;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.PreferenceSetting;
import ru.ludwigandreas.notification.service.model.RecipientProfileView;

/**
 * Manages contact records and opt-outs.
 *
 * <p>These are the two halves of the recipient model this service owns rather than borrows: the
 * identity projection knows who a user is, and this knows where to reach them and what they have
 * asked not to receive. Both are written through endpoints that are authorized and audited, because
 * an unprotected write here is either a way to redirect somebody's password reset to an attacker's
 * mailbox, or a way to silence their security alerts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipientAdminService {

    /** The category value meaning "everything this recipient may decline". */
    public static final String WILDCARD_CATEGORY = "*";

    private final RecipientProfileRepository profileRepository;
    private final RecipientPreferenceRepository preferenceRepository;

    @Transactional(readOnly = true)
    public java.util.Optional<RecipientProfileView> profile(String userId) {
        return profileRepository.lookupByUserId(userId).map(RecipientAdminService::toView);
    }

    /**
     * Creates or replaces a contact record.
     *
     * <p>Upsert rather than separate create and update endpoints, because the caller - typically the
     * user-profile service reacting to its own change event - has no reason to know whether this
     * service has seen the user before, and making it find out first would add a round trip whose
     * answer can change between the two calls.
     */
    @Transactional
    public RecipientProfileView upsertProfile(String userId, String email, String chat, String webhook,
                                              String locale, String timezone,
                                              LocalTime quietFrom, LocalTime quietTo) {
        RecipientProfileEntity profile = profileRepository.lookupByUserId(userId)
                .orElseGet(() -> RecipientProfileEntity.builder().userId(userId).build());
        profile.setEmailAddress(email);
        profile.setChatAddress(chat);
        profile.setWebhookUrl(webhook);
        profile.setLocale(locale);
        profile.setTimezone(timezone);
        profile.setQuietHoursStart(quietFrom);
        profile.setQuietHoursEnd(quietTo);

        RecipientProfileEntity saved = profileRepository.saveAndFlush(profile);
        log.info("Contact profile for {} was updated", userId);
        return toView(saved);
    }

    @Transactional(readOnly = true)
    public List<PreferenceSetting> preferences(String userId) {
        return preferenceRepository.findAllForUser(userId).stream()
                .map(RecipientAdminService::toSetting)
                .toList();
    }

    /**
     * Records one preference.
     *
     * <p>Updates the exact row rather than adding a second one, so setting the same preference twice
     * does not leave two contradictory records for the precedence rule to arbitrate between.
     *
     * @param channel  null means every channel
     * @param allowed  false is an opt-out; true is an explicit opt-in that beats a wildcard opt-out
     * @param source   how it was set - {@code self-service}, {@code support}, {@code import} - which
     *                 is what a complaint about an unwanted email is actually answered with
     */
    @Transactional
    public PreferenceSetting setPreference(String userId, String category, ChannelType channel,
                                           boolean allowed, String source) {
        ChannelKind kind = channel == null ? null : ChannelKind.valueOf(channel.name());
        RecipientPreferenceEntity preference = preferenceRepository
                .findExact(userId, category, kind)
                .orElseGet(() -> RecipientPreferenceEntity.builder()
                        .userId(userId)
                        .category(category)
                        .channel(kind)
                        .build());
        preference.setAllowed(allowed);
        preference.setSource(source);

        RecipientPreferenceEntity saved = preferenceRepository.saveAndFlush(preference);
        log.info("Preference for {} on {}/{} set to {} ({})",
                userId, category, channel == null ? "all channels" : channel, allowed, source);
        return toSetting(saved);
    }

    /**
     * Removes a preference, restoring the default.
     *
     * <p>Deleting rather than flipping to allowed: the two are different statements. "I never said
     * anything about this" and "I explicitly want this" behave identically today, and only the second
     * one survives a later wildcard opt-out - which is exactly the distinction a recipient would
     * expect and could not express if a removal were stored as an allow.
     */
    @Transactional
    public boolean clearPreference(String userId, String category, ChannelType channel) {
        ChannelKind kind = channel == null ? null : ChannelKind.valueOf(channel.name());
        return preferenceRepository.findExact(userId, category, kind)
                .map(preference -> {
                    preferenceRepository.delete(preference);
                    log.info("Preference for {} on {}/{} was cleared",
                            userId, category, channel == null ? "all channels" : channel);
                    return true;
                })
                .orElse(false);
    }

    private static RecipientProfileView toView(RecipientProfileEntity entity) {
        return new RecipientProfileView(
                entity.getId(),
                entity.getUserId(),
                entity.getEmailAddress(),
                entity.getChatAddress(),
                entity.getWebhookUrl(),
                entity.getLocale(),
                entity.getTimezone(),
                entity.getQuietHoursStart(),
                entity.getQuietHoursEnd());
    }

    private static PreferenceSetting toSetting(RecipientPreferenceEntity entity) {
        return new PreferenceSetting(
                entity.getId(),
                entity.getUserId(),
                entity.getCategory(),
                entity.getChannel() == null ? null : ChannelType.valueOf(entity.getChannel().name()),
                entity.isAllowed(),
                entity.getSource(),
                entity.getUpdatedAt());
    }
}
