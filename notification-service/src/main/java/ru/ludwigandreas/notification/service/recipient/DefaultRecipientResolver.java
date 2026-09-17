package ru.ludwigandreas.notification.service.recipient;

import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.notification.settings.NotificationProperties;
import ru.ludwigandreas.notification.repository.RecipientProfileRepository;
import ru.ludwigandreas.notification.repository.entity.RecipientProfileEntity;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.RecipientKind;
import ru.ludwigandreas.notification.service.model.RecipientRef;

/**
 * Resolves a recipient from two sources, deliberately split.
 *
 * <h2>Why two sources</h2>
 *
 * <p>{@code identity-projection-spring-boot-starter} maintains a local projection of the OIDC
 * directory, and it stores a subject, a display name, a tenant, a status and a role set -
 * deliberately nothing else. Its own documentation gives the reason: the projection is replicated
 * into every service that uses the module, so every additional field is another copy of personal data
 * and another place a deletion request has to reach. An email address, a chat handle and a home
 * timezone are not authorization inputs and have no business being copied estate-wide.
 *
 * <p>So the identity projection answers the questions it is the authority for - does this user exist,
 * are they active, what do we call them, whose tenant are they in - and
 * {@code notification_recipient_profile} answers the ones this service is the authority for: where to
 * send, in what language, and during which hours not to. That is not a workaround. This service needs
 * the contact record anyway, because preferences and quiet hours hang off it, so owning it here is
 * where it belongs rather than a duplicate of something a module should have provided.
 *
 * <h2>Degrading gracefully</h2>
 *
 * <p>A user unknown to the identity projection is <em>not</em> a failure. The projection is fed by a
 * Kafka stream, so a genuinely new user can be addressable before their record has arrived, and a
 * password-reset notification that refused to go out because a projection was three seconds behind
 * would be the worst possible outcome. When the projection has no row the notification still goes,
 * with the display name and tenant simply absent - the template sees a null it must handle
 * explicitly, which the strict renderer guarantees it does.
 *
 * <p>The one thing that does stop a delivery is having no usable address for the channel, and that is
 * reported as an empty result rather than an exception, so one unreachable recipient out of five
 * settles as a terminal delivery while the other four go out.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultRecipientResolver implements RecipientResolver {

    private final RecipientProfileRepository profileRepository;
    private final SecurityUserRepository identityRepository;
    private final NotificationProperties properties;

    @Override
    @Transactional(readOnly = true)
    public Optional<ResolvedRecipient> resolve(RecipientRef ref, ChannelType channel) {
        return ref.kind() == RecipientKind.USER ? resolveUser(ref, channel) : resolveAddress(ref, channel);
    }

    private Optional<ResolvedRecipient> resolveUser(RecipientRef ref, ChannelType channel) {
        Optional<RecipientProfileEntity> profile = profileRepository.lookupByUserId(ref.userId());
        Optional<SecurityUserEntity> identity = identityRepository.findById(ref.userId());

        if (identity.isPresent() && !identity.get().isActive()) {
            // A deactivated account is the one directory fact that must stop a send: continuing to
            // write to a leaver's address is both a privacy problem and, for a shared mailbox that
            // has been reassigned, a disclosure to whoever now reads it.
            log.debug("Recipient {} is not active in the identity projection; not delivering", ref.userId());
            return Optional.empty();
        }

        String address = profile.map(entity -> addressOf(entity, channel)).orElse(null);
        if (address == null || address.isBlank()) {
            log.debug("Recipient {} has no {} address", ref.userId(), channel);
            return Optional.empty();
        }

        if (identity.isEmpty()) {
            // Worth saying, once, at INFO: it is normal during a projection lag and abnormal if it
            // persists, and those two look identical unless the line exists to count.
            log.info("Recipient {} is unknown to the identity projection; delivering without enrichment",
                    ref.userId());
        }

        ZoneId zone = zoneOf(ref, profile.orElse(null));
        return Optional.of(new ResolvedRecipient(
                ref.userId(),
                channel,
                address,
                localeOf(ref, profile.orElse(null)),
                zone,
                identity.map(SecurityUserEntity::getDisplayName).orElse(null),
                identity.map(SecurityUserEntity::getTenantId)
                        .orElseGet(() -> profile.map(RecipientProfileEntity::getTenantId).orElse(null)),
                identity.isPresent(),
                quietHoursOf(profile.orElse(null), zone)));
    }

    /**
     * A literal destination, with no account behind it.
     *
     * <p>No profile lookup by address on purpose. A profile is keyed by subject, and searching it by
     * address would let a caller who guessed an address inherit that person's locale, timezone and
     * quiet hours - a small but real oracle, and an unnecessary one: a caller supplying a raw address
     * can supply a locale too.
     */
    private Optional<ResolvedRecipient> resolveAddress(RecipientRef ref, ChannelType channel) {
        ZoneId zone = zoneOf(ref, null);
        return Optional.of(new ResolvedRecipient(
                null,
                channel,
                ref.address(),
                localeOf(ref, null),
                zone,
                null,
                null,
                false,
                QuietHours.none(zone)));
    }

    private String addressOf(RecipientProfileEntity profile, ChannelType channel) {
        return switch (channel) {
            case EMAIL -> profile.getEmailAddress();
            case CHAT -> profile.getChatAddress();
            case WEBHOOK -> profile.getWebhookUrl();
        };
    }

    private Locale localeOf(RecipientRef ref, RecipientProfileEntity profile) {
        if (ref.locale() != null) {
            return ref.locale();
        }
        String tag = profile == null ? null : profile.getLocale();
        if (tag == null || tag.isBlank()) {
            tag = properties.getPreferences().getDefaultLocale();
        }
        return Locale.forLanguageTag(tag);
    }

    /**
     * The recipient's zone, falling back to the configured default rather than to the JVM's.
     *
     * <p>{@code ZoneId.systemDefault()} would make quiet hours depend on which region the pod happens
     * to run in, so the same recipient would be quiet at different times depending on where the
     * scheduler placed the replica that handled them.
     */
    private ZoneId zoneOf(RecipientRef ref, RecipientProfileEntity profile) {
        String candidate = ref.timezone();
        if (candidate == null || candidate.isBlank()) {
            candidate = profile == null ? null : profile.getTimezone();
        }
        if (candidate == null || candidate.isBlank()) {
            candidate = properties.getPreferences().getDefaultTimezone();
        }
        try {
            return ZoneId.of(candidate);
        } catch (DateTimeException e) {
            log.warn("Recipient carries an unusable timezone '{}'; falling back to {}",
                    candidate, properties.getPreferences().getDefaultTimezone(), e);
            return ZoneId.of(properties.getPreferences().getDefaultTimezone());
        }
    }

    private QuietHours quietHoursOf(RecipientProfileEntity profile, ZoneId zone) {
        if (profile == null || !properties.getPreferences().isQuietHoursEnabled()) {
            return QuietHours.none(zone);
        }
        return new QuietHours(profile.getQuietHoursStart(), profile.getQuietHoursEnd(), zone);
    }
}
