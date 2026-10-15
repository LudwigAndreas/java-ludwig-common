package ru.ludwigandreas.outboundtoken;

/**
 * The token endpoint refused, or could not be reached. Names the registration and the OAuth2 error
 * code, never a credential.
 */
public class TokenMintingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String registrationId;
    private final String errorCode;

    /**
     * Creates the exception.
     *
     * @param registrationId the client registration whose token endpoint was asked
     * @param errorCode      the OAuth2 error code the endpoint answered, or {@code null}
     * @param cause          the underlying failure
     */
    public TokenMintingException(String registrationId, String errorCode, Throwable cause) {
        super("The token endpoint of registration '" + registrationId + "' refused"
                + (errorCode == null ? "" : ": " + errorCode), cause);
        this.registrationId = registrationId;
        this.errorCode = errorCode;
    }

    /**
     * The registration whose token endpoint was asked.
     *
     * @return the registration id
     */
    public String registrationId() {
        return registrationId;
    }

    /**
     * The OAuth2 error code, for example {@code invalid_client}.
     *
     * @return the code, or {@code null} when the endpoint gave none
     */
    public String errorCode() {
        return errorCode;
    }
}
