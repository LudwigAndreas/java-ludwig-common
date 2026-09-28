package ru.ludwigandreas.odatafilter.parser;

import java.util.ArrayList;
import java.util.List;
import org.apache.olingo.server.core.uri.parser.UriTokenizer;
import org.apache.olingo.server.core.uri.parser.UriTokenizer.TokenKind;
import ru.ludwigandreas.odatafilter.ast.ComparisonNode;
import ru.ludwigandreas.odatafilter.ast.ComparisonOperator;
import ru.ludwigandreas.odatafilter.ast.FilterNode;
import ru.ludwigandreas.odatafilter.ast.FunctionNode;
import ru.ludwigandreas.odatafilter.ast.InNode;
import ru.ludwigandreas.odatafilter.ast.Literal;
import ru.ludwigandreas.odatafilter.ast.LiteralKind;
import ru.ludwigandreas.odatafilter.ast.LogicalNode;
import ru.ludwigandreas.odatafilter.ast.LogicalOperator;
import ru.ludwigandreas.odatafilter.ast.NotNode;
import ru.ludwigandreas.odatafilter.ast.StringFunction;
import ru.ludwigandreas.odatafilter.exception.FilterSyntaxException;

/**
 * Recursive-descent parser for a practical, enterprise-scoped subset of the OData v4
 * {@code $filter} grammar, built directly on top of Olingo's own ABNF token scanner
 * ({@link UriTokenizer}) rather than requiring a full {@code Edm} (entity data model) - which
 * is what {@code odata-server-core}'s own {@code FilterParser} needs, since it additionally
 * binds every token to CSDL metadata. Binding to a real JPA entity's properties, allowed
 * operators and roles is done afterwards, by the policy/validation layer.
 *
 * <p>Supported: {@code and}, {@code or}, {@code not}, parenthesized grouping, the six comparison
 * operators ({@code eq ne gt ge lt le}), {@code in ( ... )}, and the string functions
 * {@code contains}/{@code startswith}/{@code endswith}. Property paths may traverse to-one
 * associations with {@code /} (e.g. {@code department/name}). Not supported: arithmetic,
 * {@code any}/{@code all}, {@code $it}/{@code $root}, other string/date/math functions, and
 * property-to-property comparisons - all deliberately out of scope for a filter surface that
 * needs to be reviewable and safely bounded in production.
 */
public final class ODataFilterParser {

    public FilterNode parse(String rawFilter) {
        if (rawFilter == null || rawFilter.isBlank()) {
            throw new FilterSyntaxException("$filter must not be empty");
        }
        UriTokenizer tokenizer = new UriTokenizer(WhitespaceNormalizer.normalize(rawFilter));
        FilterNode root = parseOr(tokenizer);
        if (!tokenizer.next(TokenKind.EOF)) {
            throw new FilterSyntaxException("Unexpected trailing content in $filter: '" + rawFilter + "'");
        }
        return root;
    }

    private FilterNode parseOr(UriTokenizer t) {
        FilterNode left = parseAnd(t);
        while (t.next(TokenKind.OrOperator)) {
            left = new LogicalNode(LogicalOperator.OR, left, parseAnd(t));
        }
        return left;
    }

    private FilterNode parseAnd(UriTokenizer t) {
        FilterNode left = parseUnary(t);
        while (t.next(TokenKind.AndOperator)) {
            left = new LogicalNode(LogicalOperator.AND, left, parseUnary(t));
        }
        return left;
    }

    private FilterNode parseUnary(UriTokenizer t) {
        if (t.next(TokenKind.NotOperator)) {
            return new NotNode(parseUnary(t));
        }
        return parsePredicate(t);
    }

    private FilterNode parsePredicate(UriTokenizer t) {
        if (t.next(TokenKind.OPEN)) {
            FilterNode inner = parseOr(t);
            expect(t, TokenKind.CLOSE, ")");
            return inner;
        }
        if (t.next(TokenKind.ContainsMethod)) {
            return parseFunction(t, StringFunction.CONTAINS);
        }
        if (t.next(TokenKind.StartswithMethod)) {
            return parseFunction(t, StringFunction.STARTSWITH);
        }
        if (t.next(TokenKind.EndswithMethod)) {
            return parseFunction(t, StringFunction.ENDSWITH);
        }

        String path = PropertyPathReader.read(t);

        if (t.next(TokenKind.InOperator)) {
            return parseInList(t, path);
        }
        ComparisonOperator operator = parseComparisonOperator(t);
        Literal value = parseLiteral(t);
        return new ComparisonNode(path, operator, value);
    }

    private FilterNode parseFunction(UriTokenizer t, StringFunction function) {
        // nextMethod() already consumed "name(" for us.
        String path = PropertyPathReader.read(t);
        expect(t, TokenKind.COMMA, ",");
        if (!t.next(TokenKind.StringValue)) {
            throw new FilterSyntaxException(function + "() requires a string literal argument");
        }
        Literal literal = new Literal(LiteralKind.STRING, unquote(t.getText()));
        expect(t, TokenKind.CLOSE, ")");
        return new FunctionNode(function, path, literal);
    }

    private FilterNode parseInList(UriTokenizer t, String path) {
        expect(t, TokenKind.OPEN, "(");
        List<Literal> values = new ArrayList<>();
        values.add(parseLiteral(t));
        while (t.next(TokenKind.COMMA)) {
            values.add(parseLiteral(t));
        }
        expect(t, TokenKind.CLOSE, ")");
        return new InNode(path, values);
    }

    private ComparisonOperator parseComparisonOperator(UriTokenizer t) {
        if (t.next(TokenKind.EqualsOperator)) {
            return ComparisonOperator.EQ;
        }
        if (t.next(TokenKind.NotEqualsOperator)) {
            return ComparisonOperator.NE;
        }
        if (t.next(TokenKind.GreaterThanOrEqualsOperator)) {
            return ComparisonOperator.GE;
        }
        if (t.next(TokenKind.GreaterThanOperator)) {
            return ComparisonOperator.GT;
        }
        if (t.next(TokenKind.LessThanOrEqualsOperator)) {
            return ComparisonOperator.LE;
        }
        if (t.next(TokenKind.LessThanOperator)) {
            return ComparisonOperator.LT;
        }
        throw new FilterSyntaxException("Expected one of eq/ne/gt/ge/lt/le/in");
    }

    /** Order matters: more specific/longer literal grammars must be tried before shorter ones they subsume. */
    private Literal parseLiteral(UriTokenizer t) {
        if (t.next(TokenKind.NULL)) {
            return Literal.ofNull();
        }
        if (t.next(TokenKind.BooleanValue)) {
            return new Literal(LiteralKind.BOOLEAN, t.getText());
        }
        if (t.next(TokenKind.DateTimeOffsetValue)) {
            return new Literal(LiteralKind.DATE_TIME_OFFSET, t.getText());
        }
        if (t.next(TokenKind.DateValue)) {
            return new Literal(LiteralKind.DATE, t.getText());
        }
        if (t.next(TokenKind.GuidValue)) {
            return new Literal(LiteralKind.GUID, t.getText());
        }
        if (t.next(TokenKind.DoubleValue)) {
            return new Literal(LiteralKind.DOUBLE, t.getText());
        }
        if (t.next(TokenKind.DecimalValue)) {
            return new Literal(LiteralKind.DECIMAL, t.getText());
        }
        if (t.next(TokenKind.IntegerValue)) {
            return new Literal(LiteralKind.INTEGER, t.getText());
        }
        if (t.next(TokenKind.StringValue)) {
            return new Literal(LiteralKind.STRING, unquote(t.getText()));
        }
        throw new FilterSyntaxException("Expected a literal value");
    }

    private static String unquote(String quoted) {
        // quoted includes both surrounding single quotes; '' inside represents one literal quote.
        return quoted.substring(1, quoted.length() - 1).replace("''", "'");
    }

    private void expect(UriTokenizer t, TokenKind kind, String symbol) {
        if (!t.next(kind)) {
            throw new FilterSyntaxException("Expected '" + symbol + "'");
        }
    }
}
