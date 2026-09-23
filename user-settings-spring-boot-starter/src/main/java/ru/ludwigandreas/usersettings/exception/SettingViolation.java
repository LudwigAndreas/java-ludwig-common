package ru.ludwigandreas.usersettings.exception;

import java.util.Arrays;
import java.util.List;

/**
 * Why a value was rejected, expressed as a message code rather than a sentence.
 *
 * <p>A validator runs in the service layer, which has no locale and no business acquiring one. It
 * states the <em>reason</em>; the text is resolved at the edge from the contributed message bundles,
 * in the caller's language, by the same pipeline every other error in the platform goes through.
 *
 * @param code the message-bundle key, which is also published to the client and may be branched on
 * @param args message-format arguments - the allowed range, the permitted values, the limit
 */
public record SettingViolation(String code, List<Object> args) {

    /** Rejects a violation with no code: the code is what the message is resolved from. */
    public SettingViolation {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A setting violation needs a message code");
        }
        args = args == null ? List.of() : List.copyOf(args);
    }

    public static SettingViolation of(String code, Object... args) {
        return new SettingViolation(code, args == null ? List.of() : Arrays.asList(args));
    }

    public Object[] argArray() {
        return args.toArray();
    }
}
