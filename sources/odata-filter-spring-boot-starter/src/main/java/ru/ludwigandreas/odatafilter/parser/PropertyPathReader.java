package ru.ludwigandreas.odatafilter.parser;

import org.apache.olingo.server.core.uri.parser.UriTokenizer;
import org.apache.olingo.server.core.uri.parser.UriTokenizer.TokenKind;
import ru.ludwigandreas.odatafilter.exception.FilterSyntaxException;

/** Reads {@code segment(/segment)*} into its canonical, slash-joined form. */
final class PropertyPathReader {

    private PropertyPathReader() {
    }

    static String read(UriTokenizer tokenizer) {
        if (!tokenizer.next(TokenKind.ODataIdentifier)) {
            throw new FilterSyntaxException("Expected a property name");
        }
        StringBuilder path = new StringBuilder(tokenizer.getText());
        while (tokenizer.next(TokenKind.SLASH)) {
            if (!tokenizer.next(TokenKind.ODataIdentifier)) {
                throw new FilterSyntaxException("Expected a property name after '/' in '" + path + "/'");
            }
            path.append('/').append(tokenizer.getText());
        }
        return path.toString();
    }
}
