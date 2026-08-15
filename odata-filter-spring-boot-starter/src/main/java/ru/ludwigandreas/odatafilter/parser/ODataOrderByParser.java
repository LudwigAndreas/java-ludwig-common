package ru.ludwigandreas.odatafilter.parser;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ru.ludwigandreas.odatafilter.exception.FilterSyntaxException;

/**
 * {@code $orderby} grammar is comma-separated {@code propertyPath [asc|desc]} terms - simple
 * enough not to need a token-by-token scanner.
 */
public final class ODataOrderByParser {

    private static final Pattern TERM = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*(?:/[A-Za-z_][A-Za-z0-9_]*)*)(?:\\s+(asc|desc))?",
            Pattern.CASE_INSENSITIVE);

    public List<OrderByTerm> parse(String rawOrderBy) {
        if (rawOrderBy == null || rawOrderBy.isBlank()) {
            return List.of();
        }
        List<OrderByTerm> terms = new ArrayList<>();
        for (String part : rawOrderBy.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                throw new FilterSyntaxException("Empty $orderby term in '" + rawOrderBy + "'");
            }
            Matcher matcher = TERM.matcher(trimmed);
            if (!matcher.matches()) {
                throw new FilterSyntaxException("Malformed $orderby term: '" + trimmed + "'");
            }
            boolean descending = "desc".equalsIgnoreCase(matcher.group(2));
            terms.add(new OrderByTerm(matcher.group(1), descending));
        }
        return terms;
    }
}
