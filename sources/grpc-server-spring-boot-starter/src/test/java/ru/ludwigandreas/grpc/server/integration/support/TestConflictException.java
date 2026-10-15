package ru.ludwigandreas.grpc.server.integration.support;

/**
 * A domain failure the test application maps to a 409 problem.
 */
public class TestConflictException extends RuntimeException {

    public static final String CODE = "test.error.conflict";

    private static final long serialVersionUID = 1L;

    public TestConflictException() {
        super("conflict");
    }
}
