package ru.ludwigandreas.usersettings.api;

import ru.ludwigandreas.usersettings.exception.SettingViolation;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import ru.ludwigandreas.usersettings.exception.SettingProblemCodes;

/**
 * A constraint a setting's value has to satisfy, declared next to the definition it belongs to.
 *
 * <p>Deliberately not Bean Validation. A setting's value is a {@code T} handed to a generic engine,
 * not a field on a bean the engine knows the shape of, so there is nothing for {@code @Min} to
 * annotate - and the alternative of validating the persisted string would check the encoding rather
 * than the value. Declaring the rule as a function alongside the type it applies to also means the
 * default value is checked by exactly the same code at startup that a user's submission is checked
 * by at runtime, which is what makes "a default that fails its own validation" a startup failure.
 *
 * @param <T> the setting's value type
 */
@FunctionalInterface
public interface SettingValidator<T> {

    /**
     * Checks one candidate value.
     *
     * @param value the candidate value; may be {@code null} when a setting is being reset
     * @return empty when the value is acceptable, otherwise the reason
     */
    Optional<SettingViolation> validate(T value);

    /** Rejects {@code null}. Values are otherwise allowed to be absent. */
    static <T> SettingValidator<T> required() {
        return value -> value == null
                ? Optional.of(SettingViolation.of(SettingProblemCodes.VALIDATION_REQUIRED))
                : Optional.empty();
    }

    /**
     * Restricts the value to a fixed set.
     *
     * <p>The permitted values are passed to the message as a joined string rather than as a
     * collection, so the rendered problem reads as a sentence instead of as a {@code toString()} of
     * a set.
     */
    static <T> SettingValidator<T> oneOf(Collection<T> permitted) {
        Set<T> allowed = new LinkedHashSet<>(permitted);
        return value -> value == null || allowed.contains(value)
                ? Optional.empty()
                : Optional.of(SettingViolation.of(SettingProblemCodes.VALIDATION_ONE_OF, join(allowed)));
    }

    /** Restricts the value to an inclusive range. */
    static <T extends Comparable<T>> SettingValidator<T> range(T min, T max) {
        return value -> {
            if (value == null) {
                return Optional.empty();
            }
            boolean belowMin = min != null && value.compareTo(min) < 0;
            boolean aboveMax = max != null && value.compareTo(max) > 0;
            return belowMin || aboveMax
                    ? Optional.of(SettingViolation.of(SettingProblemCodes.VALIDATION_RANGE, min, max))
                    : Optional.empty();
        };
    }

    /** Bounds a textual value's length, so one setting cannot be used as general-purpose storage. */
    static SettingValidator<String> maxLength(int max) {
        return value -> value == null || value.length() <= max
                ? Optional.empty()
                : Optional.of(SettingViolation.of(SettingProblemCodes.VALIDATION_MAX_LENGTH, max));
    }

    /**
     * Requires the whole value to match {@code pattern}.
     *
     * <p>The pattern itself is <em>not</em> passed to the message. A regular expression is not an
     * explanation, and echoing one into a problem document tells a caller how the check works
     * without telling them what to send instead; a definition wanting better wording supplies its
     * own validator with its own code.
     */
    static SettingValidator<String> matching(Pattern pattern) {
        return value -> value == null || pattern.matcher(value).matches()
                ? Optional.empty()
                : Optional.of(SettingViolation.of(SettingProblemCodes.VALIDATION_PATTERN));
    }

    /** Applies each validator in turn and reports the first rejection. */
    @SafeVarargs
    static <T> SettingValidator<T> all(SettingValidator<T>... validators) {
        List<SettingValidator<T>> chain = List.of(validators);
        return value -> chain.stream()
                .map(validator -> validator.validate(value))
                .filter(Optional::isPresent)
                .findFirst()
                .orElseGet(Optional::empty);
    }

    private static String join(Collection<?> values) {
        StringBuilder joined = new StringBuilder();
        for (Object value : values) {
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(value);
        }
        return joined.toString();
    }
}
