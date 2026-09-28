package ru.ludwigandreas.usersettings.exception;

/**
 * A settings configuration that cannot be served correctly: a duplicate key, a default that fails
 * its own validation, a type nothing can convert.
 *
 * <p>Thrown during context startup, never later. The alternative - discovering a broken definition
 * the first time somebody reads it - means the failure surfaces as a 500 on one user's page, in
 * production, at whatever hour that user happens to log in, instead of in the deployment that
 * introduced it. Every check that can be moved to startup is worth moving, even when it makes a bad
 * deploy fail louder.
 */
public class SettingConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SettingConfigurationException(String message) {
        super(message);
    }

    public SettingConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
