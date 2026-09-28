package ru.ludwigandreas.jira.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The payload for creating or updating a Jira Server user.
 *
 * <p>Creating users through the REST API only works when the account's directory is Jira's internal one; a
 * user backed by LDAP or Crowd is read-only from here and the call fails with a 400 that blames the
 * directory. On this platform, where an
 * identity-provider service owns accounts, this endpoint is normally the wrong tool - projecting users from
 * that service's event stream is the supported path.
 *
 * <p>Omitting {@code password} makes Jira email the new account a password-reset link, which is the right
 * behaviour for a human and the wrong one for a service account.
 *
 * @param name the username, immutable afterwards on most directories
 * @param password initial password, or {@code null} to have Jira send a reset link
 * @param emailAddress email address, required
 * @param displayName display name, required
 * @param applicationKeys which applications the account is licensed for
 */
public record UserInput(@JsonProperty("name") String name,
                        @JsonProperty("password") String password,
                        @JsonProperty("emailAddress") String emailAddress,
                        @JsonProperty("displayName") String displayName,
                        @JsonProperty("applicationKeys") List<String> applicationKeys) {
}
