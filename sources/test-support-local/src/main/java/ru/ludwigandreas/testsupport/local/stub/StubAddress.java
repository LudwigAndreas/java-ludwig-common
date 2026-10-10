package ru.ludwigandreas.testsupport.local.stub;

import java.util.Locale;
import java.util.Optional;

/**
 * Where a stub's address is published, which is to say how the service finds the stand-in.
 *
 * <p>There are three cases and they are checked differently, which is why this is a type and not a
 * nullable property name:
 *
 * <ul>
 *   <li>{@link #restClient()} - the stub stands in for a {@code @LudwigRestClient}; its address
 *       becomes that client's {@code base-url}, and the client-implies-stub rule counts it;</li>
 *   <li>{@link #property(String)} - the partner is reached through a client the service builds
 *       itself, addressed by a property of the service's own;</li>
 *   <li>{@link #none()} - nothing in configuration names the partner. A webhook receiver is the case:
 *       the URL lives in a recipient's profile, so a fixture reads the stub's address and writes it
 *       there.</li>
 * </ul>
 *
 * <p>"No address on purpose" is thereby distinguishable from "forgot", and the launcher can refuse a
 * stub whose property nothing else configures - what a renamed property leaves behind.
 */
public final class StubAddress {

    private static final String REST_CLIENT_TEMPLATE = "ludwig.rest-client.clients.%s.base-url";
    private static final StubAddress REST_CLIENT = new StubAddress(Kind.REST_CLIENT, null);
    private static final StubAddress NONE = new StubAddress(Kind.NONE, null);

    private final Kind kind;
    private final String property;

    private StubAddress(Kind kind, String property) {
        this.kind = kind;
        this.property = property;
    }

    /**
     * The stub stands in for the rest client that has the stub's name.
     *
     * @return the address
     */
    public static StubAddress restClient() {
        return REST_CLIENT;
    }

    /**
     * The stub's address is published under a property the service reads.
     *
     * @param name the property name, as the service's configuration spells it
     * @return the address
     */
    public static StubAddress property(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A stub's address property must be named. Use "
                    + "StubAddress.none() for a stub nothing in configuration points at.");
        }
        return new StubAddress(Kind.PROPERTY, name);
    }

    /**
     * Nothing in configuration names this partner; fixtures and tests hand the address out.
     *
     * @return the address
     */
    public static StubAddress none() {
        return NONE;
    }

    /**
     * Whether the stub stands in for a rest client.
     *
     * @return {@code true} for {@link #restClient()}
     */
    public boolean isRestClient() {
        return kind == Kind.REST_CLIENT;
    }

    /**
     * The property the stub's address is published under.
     *
     * @param stubName the stub's name, which for a rest-client stub is the client's name
     * @return the property name, or empty for {@link #none()}
     */
    public Optional<String> propertyFor(String stubName) {
        return switch (kind) {
            case REST_CLIENT -> Optional.of(String.format(Locale.ROOT, REST_CLIENT_TEMPLATE, stubName));
            case PROPERTY -> Optional.of(property);
            case NONE -> Optional.empty();
        };
    }

    private enum Kind { REST_CLIENT, PROPERTY, NONE }
}
