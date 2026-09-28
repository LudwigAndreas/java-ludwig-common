package ru.ludwigandreas.notification.service.recipient;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ludwigandreas.identity.entity.SecurityUserEntity;
import ru.ludwigandreas.identity.repository.SecurityUserRepository;
import ru.ludwigandreas.notification.service.model.ChannelType;
import ru.ludwigandreas.notification.service.model.RecipientKind;
import ru.ludwigandreas.notification.service.model.RecipientRef;
import ru.ludwigandreas.notification.service.preference.QuietHours;
import ru.ludwigandreas.notification.service.preference.QuietHoursWindow;
import ru.ludwigandreas.notification.service.preference.RecipientPreferenceSource;
import ru.ludwigandreas.notification.service.preference.RecipientPreferences;
import ru.ludwigandreas.notification.service.preference.StoredPreferences;
import ru.ludwigandreas.notification.settings.NotificationProperties;

/**
 * Resolves a recipient from two sources, neither of which is this service's own table.
 *
 * <h2>Where the two halves come from</h2>
 *
 * <p><b>The address</b> comes from {@code identity-projection-spring-boot-starter}: the OIDC provider
 * verifies a person's email and phone, so it is the single source of truth for them, and this service
 * reads the projection of that rather than keeping its own contact table. It used to keep one, and the
 * argument for it - that the projection should not carry personal data into every service - was right
 * about the general case and wrong about the answer. The projection now carries contact data only
 * where a deployment asks for it ({@code ludwig.identity.contact.enabled}), which is this service and
 * nothing else, so the estate-wide copy never happens and there is still exactly one place an address
 * is correct.
 *
 * <p><b>The preferences</b> come from a {@link RecipientPreferenceSource}, which is a seam rather
 * than a dependency. Where the platform runs a preference store the adapter reads it locally; where
 * it does not - and that is the shape this service is currently deployed in - the source answers
 * "nothing stored" and every recipient resolves to the configured defaults. Either way no call
 * leaves this process on the dispatch path, which is the property that matters on a queue worker.
 *
 * <h2>Precedence, and why it is this way round</h2>
 *
 * <p>For locale and timezone: the request's hint first, then what the recipient chose, then
 * configuration. The hint wins because a calling service that names a locale has usually been told
 * it by the very interaction that triggered the notification - the language the person was reading
 * the page in when they asked for a password reset - and that is fresher than a profile setting they
 * last touched a year ago.
 *
 * <p>For quiet hours the order is reversed and there is no hint at all: a caller does not get to say
 * when it is acceptable to disturb somebody. See {@code PreferenceEvaluator} for the same principle
 * applied to the transactional bypass.
 *
 * <h2>Degrading gracefully</h2>
 *
 * <p>A user unknown to the identity projection is <em>not</em> a failure. A genuinely new user can be
 * addressable before their record has arrived, and a password-reset notification refused because a
 * projection was three seconds behind would be the worst possible outcome. With no row the
 * notification still goes when the caller supplied a literal address; with no row and no address
 * there is nothing to send to, and that settles as a terminal delivery rather than a rejected
 * request.
 *
 * <p>An unverified address is treated as no address at all when
 * {@code ludwig.notification.recipients.require-verified-contact} is on. Verification is the
 * provider's job and this service does not second-guess it; what it decides is whether to write to an
 * address nobody has confirmed belongs to the person.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultRecipientResolver implements RecipientResolver {

    private final SecurityUserRepository identityRepository;
    private final RecipientPreferenceSource preferenceSource;
    private final NotificationProperties properties;

    /**
     * Resolves a recipient's preferences once, before the fan-out reaches any channel.
     *
     * <p>Separate from {@link #resolve} precisely so it is called once per recipient rather than once
     * per recipient per channel. It may be one query either way where the source caches, but the
     * shape of the call is what keeps it that way when somebody adds a fourth channel.
     */
    @Override
    @Transactional(readOnly = true)
    public RecipientPreferences preferences(RecipientRef ref) {
        if (ref.kind() != RecipientKind.USER) {
            // A literal address has nobody behind it to have preferences. Its locale and zone come
            // from the caller or from configuration, which is all that is knowable about it.
            return build(ref, StoredPreferences.none());
        }

        String tenantId = identityRepository.findById(ref.userId())
                .map(SecurityUserEntity::getTenantId)
                .orElse(null);
        return build(ref, preferenceSource.lookup(ref.userId(), tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResolvedRecipient> resolve(RecipientRef ref, ChannelType channel,
                                               RecipientPreferences preferences) {
        return ref.kind() == RecipientKind.USER
                ? resolveUser(ref, channel, preferences)
                : resolveAddress(ref, channel, preferences);
    }

    /** The request's hints and the stored preferences, folded into what the fan-out needs. */
    private RecipientPreferences build(RecipientRef ref, StoredPreferences stored) {
        ZoneId zone = zoneOf(ref, stored);
        return new RecipientPreferences(
                localeOf(ref, stored),
                zone,
                quietHoursOf(stored, zone),
                stored.digest(),
                stored.optOuts());
    }

    private Optional<ResolvedRecipient> resolveUser(RecipientRef ref, ChannelType channel,
                                                    RecipientPreferences preferences) {
        Optional<SecurityUserEntity> identity = identityRepository.findById(ref.userId());

        if (identity.isPresent() && !identity.get().isActive()) {
            // A deactivated account is the one directory fact that must stop a send: continuing to
            // write to a leaver's address is both a privacy problem and, for a shared mailbox that
            // has been reassigned, a disclosure to whoever now reads it.
            log.debug("Recipient {} is not active in the identity projection; not delivering", ref.userId());
            return Optional.empty();
        }

        String address = identity.map(user -> addressOf(user, channel)).orElse(null);
        if (address == null || address.isBlank()) {
            log.debug("Recipient {} has no usable {} address", ref.userId(), channel);
            return Optional.empty();
        }

        return Optional.of(new ResolvedRecipient(
                ref.userId(),
                channel,
                address,
                preferences.locale(),
                preferences.zone(),
                identity.map(SecurityUserEntity::getDisplayName).orElse(null),
                identity.map(SecurityUserEntity::getTenantId).orElse(null),
                identity.isPresent(),
                preferences));
    }

    /**
     * A literal destination, with no account behind it.
     *
     * <p>No preference lookup by address on purpose. Preferences are keyed by subject, and searching
     * them by address would let a caller who guessed an address inherit that person's locale,
     * timezone and quiet hours - a small but real oracle, and an unnecessary one: a caller supplying a
     * raw address can supply a locale too.
     */
    private Optional<ResolvedRecipient> resolveAddress(RecipientRef ref, ChannelType channel,
                                                       RecipientPreferences preferences) {
        return Optional.of(new ResolvedRecipient(
                null,
                channel,
                ref.address(),
                preferences.locale(),
                preferences.zone(),
                null,
                null,
                false,
                preferences));
    }

    /**
     * The address for this channel, honouring the provider's verification flag.
     *
     * <p>The alternate address is the fallback for email, because an internal address that does not
     * accept external mail is a common shape and the point of having two is that one of them works.
     * Webhooks have no per-user address at all: a webhook is a machine destination, and a request that
     * wants one names it literally.
     */
    private String addressOf(SecurityUserEntity user, ChannelType channel) {
        return switch (channel) {
            case EMAIL -> verified(user.getEmail(), user.getEmailVerified()) != null
                    ? user.getEmail()
                    : verified(user.getAlternateEmail(), user.getEmailVerified());
            case CHAT -> user.getChatHandle();
            case WEBHOOK -> null;
        };
    }

    private String verified(String address, Boolean addressVerified) {
        if (address == null || address.isBlank()) {
            return null;
        }
        if (!properties.getRecipients().isRequireVerifiedContact()) {
            return address;
        }
        // Null means the provider did not say, which is not the same as saying no. Treating silence as
        // unverified would stop every message the moment an older producer omitted the field.
        return Boolean.FALSE.equals(addressVerified) ? null : address;
    }

    private Locale localeOf(RecipientRef ref, StoredPreferences stored) {
        if (ref.locale() != null) {
            return ref.locale();
        }
        if (stored.locale() != null) {
            return stored.locale();
        }
        return Locale.forLanguageTag(properties.getPreferences().getDefaultLocale());
    }

    /**
     * The recipient's zone, falling back to the configured default rather than to the JVM's.
     *
     * <p>{@code ZoneId.systemDefault()} would make quiet hours depend on which region the pod happens
     * to run in, so the same recipient would be quiet at different times depending on where the
     * scheduler placed the replica that handled them.
     */
    private ZoneId zoneOf(RecipientRef ref, StoredPreferences stored) {
        if (ref.timezone() != null && !ref.timezone().isBlank()) {
            return parse(ref.timezone());
        }
        if (stored.zone() != null) {
            return stored.zone();
        }
        return parse(properties.getPreferences().getDefaultTimezone());
    }

    private ZoneId parse(String candidate) {
        try {
            return ZoneId.of(candidate);
        } catch (DateTimeException e) {
            log.warn("Recipient carries an unusable timezone '{}'; falling back to {}",
                    candidate, properties.getPreferences().getDefaultTimezone(), e);
            return ZoneId.of(properties.getPreferences().getDefaultTimezone());
        }
    }

    /**
     * The recipient's quiet window, taken from the stored preference and expressed in their own zone.
     *
     * <p>The store carries a window; this service carries the evaluation, because "is this instant
     * inside it" needs a zone and a preference store has no business knowing about instants.
     */
    private QuietHours quietHoursOf(StoredPreferences stored, ZoneId zone) {
        if (!properties.getPreferences().isQuietHoursEnabled()) {
            return QuietHours.none(zone);
        }
        QuietHoursWindow window = stored.quietHours();
        if (!window.isConfigured()) {
            return QuietHours.none(zone);
        }
        return new QuietHours(window.start(), window.end(), zone);
    }
}
