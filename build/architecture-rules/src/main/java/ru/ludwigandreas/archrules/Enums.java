package ru.ludwigandreas.archrules;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Lookup of the kebab-case identifiers that the configuration properties use. */
final class Enums {

    private Enums() {
    }

    static <E extends Enum<E>> Optional<E> byId(E[] values, Function<E, String> idExtractor, String id) {
        return Arrays.stream(values)
                .filter(value -> idExtractor.apply(value).equals(id))
                .findFirst();
    }

    static <E extends Enum<E>> List<String> ids(E[] values, Function<E, String> idExtractor) {
        return Arrays.stream(values).map(idExtractor).collect(Collectors.toList());
    }
}
