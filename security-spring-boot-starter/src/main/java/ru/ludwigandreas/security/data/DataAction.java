package ru.ludwigandreas.security.data;

/**
 * The conventional action names policies are written against.
 *
 * <p>Plain string constants rather than an enum: a service is expected to add its own verbs
 * ({@code "approve"}, {@code "export"}) when read/write/delete are too coarse, and an enum here would
 * force every such verb through this module.
 *
 * <p>The distinction matters more than it looks. Read and write scopes are routinely different for
 * the same role - an agent reads every case in their queue but writes only the ones assigned to them -
 * and collapsing them into one "access" concept is what makes people grant the wider of the two.
 */
public final class DataAction {

    public static final String READ = "read";
    public static final String WRITE = "write";
    public static final String DELETE = "delete";

    private DataAction() {
    }
}
