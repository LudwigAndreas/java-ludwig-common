package ru.ludwigandreas.jira.auth;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.function.Supplier;
import ru.ludwigandreas.jira.http.JiraRequest;

/**
 * HTTP Basic authentication with a Jira username and password.
 *
 * <p>Supported because plenty of Jira Server deployments still run integration accounts this way, but
 * {@link PersonalAccessTokenCredentials} is the better choice wherever the instance is 8.14 or newer. Two
 * operational hazards come with Basic auth and neither is something a client can work around:
 *
 * <ul>
 *   <li>After a few failed logins Jira raises a CAPTCHA challenge on the account, after which every API
 *       call returns 403 with {@code X-Authentication-Denied-Reason: CAPTCHA_CHALLENGE} until a human
 *       clears it in the web UI. This is why a 401 is never retried by this client.</li>
 *   <li>The password is replayed on every single request, so it is exposed to every proxy, log and heap
 *       dump along the path, and rotating it means coordinating a restart of every consumer.</li>
 * </ul>
 *
 * <p>The header is encoded per request rather than cached so that {@link #rotating} works and so the
 * credential does not sit in a long-lived {@code String} any longer than the request does.
 */
public final class BasicAuthCredentials implements JiraCredentials {

    private final String username;
    private final Supplier<char[]> password;

    private BasicAuthCredentials(String username, Supplier<char[]> password) {
        this.username = username;
        this.password = password;
    }

    /**
     * Fixed username and password.
     *
     * @param username Jira username, not an email address - Jira Server identifies users by username
     * @param password the account's password
     * @return credentials sending {@code Authorization: Basic ...}
     */
    public static BasicAuthCredentials of(String username, String password) {
        Objects.requireNonNull(password, "password");
        char[] chars = password.toCharArray();
        return rotating(username, () -> chars.clone());
    }

    /**
     * A password re-read before every attempt, for a rotating secret.
     *
     * @param username Jira username
     * @param password supplier of the current password
     * @return credentials sending {@code Authorization: Basic ...}
     */
    public static BasicAuthCredentials rotating(String username, Supplier<char[]> password) {
        String name = Objects.requireNonNull(username, "username").strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Username must not be blank");
        }
        return new BasicAuthCredentials(name, Objects.requireNonNull(password, "password"));
    }

    @Override
    public void apply(JiraRequest.Builder request) {
        char[] current = password.get();
        if (current == null) {
            throw new IllegalStateException("Password supplier returned no password");
        }
        try {
            String joined = username + ":" + new String(current);
            byte[] raw = joined.getBytes(StandardCharsets.UTF_8);
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(raw));
        } finally {
            java.util.Arrays.fill(current, '\0');
        }
    }

    @Override
    public String describe() {
        return "basic auth as '" + username + "'";
    }
}
