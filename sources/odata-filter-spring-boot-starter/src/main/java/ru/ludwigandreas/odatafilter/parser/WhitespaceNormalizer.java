package ru.ludwigandreas.odatafilter.parser;

/**
 * {@link org.apache.olingo.server.core.uri.parser.UriTokenizer} does not skip "insignificant"
 * whitespace (OData's {@code BWS}) around {@code (}, {@code )} and {@code ,} - it only consumes
 * the mandatory whitespace ({@code RWS}) around keyword operators such as {@code and}/{@code or}/
 * {@code not}/{@code eq} as part of matching those tokens. Real-world clients (and humans) do
 * sometimes write {@code contains( Name, 'foo' )}, so this normalizes it away before tokenizing.
 *
 * <p>Only whitespace immediately after {@code (}, immediately before {@code )}, and surrounding
 * {@code ,} is removed, and only outside of string literals. Whitespace elsewhere - crucially,
 * the single space that can sit between a keyword like {@code and}/{@code not} and a following
 * {@code (} - is left untouched, because the tokenizer already consumes runs of it as part of
 * matching the keyword; stripping it there would delete whitespace the tokenizer requires.
 */
final class WhitespaceNormalizer {

    private WhitespaceNormalizer() {
    }

    static String normalize(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        StringBuilder outside = new StringBuilder();
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (c == '\'') {
                out.append(normalizeOutside(outside));
                outside.setLength(0);
                int start = i;
                i++;
                while (i < n) {
                    if (raw.charAt(i) == '\'') {
                        if (i + 1 < n && raw.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append(raw, start, i);
            } else {
                outside.append(c);
                i++;
            }
        }
        out.append(normalizeOutside(outside));
        return out.toString();
    }

    private static String normalizeOutside(CharSequence segment) {
        return segment.toString()
                .replaceAll("\\(\\s+", "(")
                .replaceAll("\\s+\\)", ")")
                .replaceAll("\\s*,\\s*", ",");
    }
}
