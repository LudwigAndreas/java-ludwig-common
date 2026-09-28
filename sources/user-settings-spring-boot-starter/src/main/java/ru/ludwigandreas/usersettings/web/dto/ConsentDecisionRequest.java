package ru.ludwigandreas.usersettings.web.dto;

/**
 * A consent being granted or withdrawn.
 *
 * <p>{@code textVersion} is required by the record it creates, not by this DTO being fussy: a
 * decision without the version of the wording it was made against cannot later answer what the
 * subject actually agreed to.
 *
 * <p>The evidence - address, user agent, correlation id - is taken from the request itself and is
 * deliberately not accepted from the client. A caller that could state its own evidence could state
 * somebody else's.
 */
public record ConsentDecisionRequest(String consentKey, String textVersion, String locale) {
}
