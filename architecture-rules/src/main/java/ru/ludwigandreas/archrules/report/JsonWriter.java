package ru.ludwigandreas.archrules.report;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A minimal, correct JSON writer.
 *
 * <p>Hand-written because this library has no third-party dependencies beyond ArchUnit itself and is
 * not going to put Jackson on every consuming service's test classpath for one file. The output is
 * indented rather than compact: the JSON report is read by tools, but also diffed in pull requests and
 * skimmed by people.
 */
final class JsonWriter {

    private final StringBuilder out = new StringBuilder();
    private final Deque<boolean[]> scopes = new ArrayDeque<>();
    private boolean pendingName;
    private int indent;

    JsonWriter beginObject() {
        beforeValue();
        out.append('{');
        openScope();
        return this;
    }

    JsonWriter endObject() {
        closeScope('}');
        return this;
    }

    JsonWriter beginArray() {
        beforeValue();
        out.append('[');
        openScope();
        return this;
    }

    JsonWriter endArray() {
        closeScope(']');
        return this;
    }

    JsonWriter name(String name) {
        separate();
        out.append('"').append(escape(name)).append("\": ");
        pendingName = true;
        return this;
    }

    JsonWriter value(String value) {
        beforeValue();
        if (value == null) {
            out.append("null");
        } else {
            out.append('"').append(escape(value)).append('"');
        }
        return this;
    }

    JsonWriter value(long value) {
        beforeValue();
        out.append(value);
        return this;
    }

    JsonWriter value(boolean value) {
        beforeValue();
        out.append(value);
        return this;
    }

    JsonWriter field(String name, String value) {
        return name(name).value(value);
    }

    JsonWriter field(String name, long value) {
        return name(name).value(value);
    }

    JsonWriter field(String name, boolean value) {
        return name(name).value(value);
    }

    String toJson() {
        return out + System.lineSeparator();
    }

    private void openScope() {
        scopes.push(new boolean[]{true});
        indent++;
    }

    private void closeScope(char closing) {
        boolean[] scope = scopes.pop();
        indent--;
        if (!scope[0]) {
            newLine();
        }
        out.append(closing);
    }

    /** A value that follows a name is written inline; anything else is a fresh array element. */
    private void beforeValue() {
        if (pendingName) {
            pendingName = false;
            return;
        }
        separate();
    }

    private void separate() {
        if (scopes.isEmpty()) {
            return;
        }
        boolean[] scope = scopes.peek();
        if (scope[0]) {
            scope[0] = false;
        } else {
            out.append(',');
        }
        newLine();
    }

    private void newLine() {
        out.append(System.lineSeparator()).append("  ".repeat(Math.max(0, indent)));
    }

    static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
