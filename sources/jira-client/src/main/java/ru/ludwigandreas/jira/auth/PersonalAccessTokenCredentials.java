package ru.ludwigandreas.jira.auth;

import java.util.Objects;
import java.util.function.Supplier;
import ru.ludwigandreas.jira.http.JiraRequest;

/**
 * Bearer authentication with a Jira Server personal access token, available since Jira 8.14 and the
 * recommended scheme on 9.12.
 *
 * <p>Preferred over {@link BasicAuthCredentials} for a service account for three reasons that matter in
 * production: a PAT can be scoped and expired without touching the account's password, it is not accepted
 * by Jira's non-REST endpoints so a leaked token cannot be used to log into the web UI, and it does not
 * trip Jira's CAPTCHA-after-failed-logins protection - which, with Basic auth, locks an integration
 * account out of every subsequent call after a handful of failures.
 *
 * <p>Use {@link #rotating(Supplier)} when the token comes from a secrets manager with a lease. The supplier
 * is consulted on every attempt, so a rotation that happens mid-retry is picked up rather than producing
 * three 401s in a row.
 */
public final class PersonalAccessTokenCredentials implements JiraCredentials {

    private final Supplier<String> token;

    private PersonalAccessTokenCredentials(Supplier<String> token) {
        this.token = token;
    }

    /**
     * A fixed token, read once at construction.
     *
     * @param token the personal access token
     * @return credentials sending {@code Authorization: Bearer <token>}
     */
    public static PersonalAccessTokenCredentials of(String token) {
        String value = Objects.requireNonNull(token, "token").strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Personal access token must not be blank");
        }
        return new PersonalAccessTokenCredentials(() -> value);
    }

    /**
     * A token re-read before every attempt, for a Vault lease or any other rotating secret.
     *
     * @param token supplier of the current token
     * @return credentials sending {@code Authorization: Bearer <token>}
     */
    public static PersonalAccessTokenCredentials rotating(Supplier<String> token) {
        return new PersonalAccessTokenCredentials(Objects.requireNonNull(token, "token"));
    }

    @Override
    public void apply(JiraRequest.Builder request) {
        String current = token.get();
        if (current == null || current.isBlank()) {
            throw new IllegalStateException("Personal access token supplier returned no token");
        }
        request.header("Authorization", "Bearer " + current.strip());
    }

    @Override
    public String describe() {
        return "personal access token";
    }
}
