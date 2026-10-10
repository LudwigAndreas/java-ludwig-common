package ru.ludwigandreas.testsupport.local.stub;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The ways a partner stub can be made to fail.
 *
 * <p>Each constant's lower-case name is the value of {@code ludwig.local.stubs.<name>.fault} and, for
 * every fault but {@link #REFUSED}, the state of the stub's {@code fault} scenario - so the property,
 * the Java API and WireMock's admin API all name a fault the same way.
 */
public enum StubFault {

    /** Every request is answered with HTTP 503. */
    UNAVAILABLE,

    /**
     * Every request is answered with HTTP 503 after {@code ludwig.local.stubs.<name>.slow-delay}.
     *
     * <p>This is for exceeding a client's read timeout, which is why the eventual answer is not the
     * healthy one: a client that waited long enough to see it has no timeout to test.
     */
    SLOW,

    /** The connection is reset before any response is written. */
    RESET,

    /** The connection is closed cleanly without a response. */
    EMPTY,

    /**
     * Nothing listens on the stub's port.
     *
     * <p>Realised by stopping the listener, which takes the admin API with it. It can therefore be set
     * from test code and from the start-up property, never over HTTP, and never for one path.
     */
    REFUSED;

    /**
     * The name this fault goes by in configuration and as a scenario state.
     *
     * @return the constant's name in lower case
     */
    public String state() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves a configured fault name.
     *
     * @param value the configured value
     * @return the fault
     * @throws IllegalArgumentException naming the valid values when {@code value} is not one of them
     */
    public static StubFault parse(String value) {
        return fromState(value).orElseThrow(() -> new IllegalArgumentException(
                "'" + value + "' is not a stub fault. Valid values: " + validNames()));
    }

    /**
     * Resolves a scenario state back to the fault it stands for.
     *
     * @param state a scenario state
     * @return the fault, or empty for the scenario's start state and anything else
     */
    public static Optional<StubFault> fromState(String state) {
        if (state == null) {
            return Optional.empty();
        }
        String wanted = state.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(fault -> fault.state().equals(wanted)).findFirst();
    }

    private static String validNames() {
        return Arrays.stream(values()).map(StubFault::state).collect(Collectors.joining(", "));
    }
}
