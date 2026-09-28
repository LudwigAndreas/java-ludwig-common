package ru.ludwigandreas.jira.jql;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A JQL function call, rendered unquoted: {@code currentUser()}, {@code membersOf("jira-admins")}.
 *
 * <p>Constructed through {@link JqlFunctions}, which is the catalogue of the functions Jira Server 9.12
 * ships. This class is public so that a marketplace app's function - Structure's {@code structure()},
 * Tempo's {@code tempoTeam()} - can be expressed without waiting for this library to add it:
 * {@code JqlFunction.of("structure", "My Structure")} renders {@code structure("My Structure")} with the
 * argument escaped, which is safer than {@link JqlValue#raw(String)}.
 */
public final class JqlFunction {

    private final String name;
    private final List<String> arguments;

    private JqlFunction(String name, List<String> arguments) {
        this.name = name;
        this.arguments = List.copyOf(arguments);
    }

    /**
     * A function call with string arguments, each quoted and escaped.
     *
     * @param name the function name, without parentheses
     * @param arguments the arguments, quoted and escaped as JQL string literals
     * @return the function call
     */
    public static JqlFunction of(String name, String... arguments) {
        return new JqlFunction(name, Arrays.stream(arguments).map(JqlValue::quote).toList());
    }

    /**
     * A function call whose arguments are already rendered - a number, or a nested function.
     *
     * @param name the function name, without parentheses
     * @param renderedArguments arguments inserted verbatim
     * @return the function call
     */
    public static JqlFunction raw(String name, String... renderedArguments) {
        return new JqlFunction(name, List.of(renderedArguments));
    }

    /** The rendered call, for example {@code membersOf("jira-admins")}. */
    public String render() {
        return name + "(" + arguments.stream().collect(Collectors.joining(", ")) + ")";
    }

    /** This function as a clause value. */
    public JqlValue asValue() {
        return JqlValue.function(this);
    }

    @Override
    public String toString() {
        return render();
    }
}
