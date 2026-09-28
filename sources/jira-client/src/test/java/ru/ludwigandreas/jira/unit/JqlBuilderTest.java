package ru.ludwigandreas.jira.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.ludwigandreas.jira.jql.Jql;
import ru.ludwigandreas.jira.jql.JqlClause;
import ru.ludwigandreas.jira.jql.JqlFunctions;
import ru.ludwigandreas.jira.jql.JqlQuery;
import ru.ludwigandreas.jira.jql.JqlValue;

class JqlBuilderTest {

    @Test
    void rendersATypicalQueryTheWayItReads() {
        String jql = JqlQuery.builder()
                .where(Jql.project().in("OPS", "PLAT"))
                .where(Jql.status().notIn("Done", "Closed"))
                .where(Jql.assignee().isCurrentUser())
                .where(Jql.created().after("-30d"))
                .orderByStableWalk()
                .render();

        assertThat(jql).isEqualTo("project IN (\"OPS\", \"PLAT\") "
                + "AND status NOT IN (\"Done\", \"Closed\") "
                + "AND assignee = currentUser() "
                + "AND created > \"-30d\" "
                + "ORDER BY created ASC, issuekey ASC");
    }

    @Test
    void escapesQuotesAndBackslashesSoAValueCannotBreakOutOfItsLiteral() {
        String jql = Jql.summary().contains("he said \"5\\\" wide\"").render();

        assertThat(jql).isEqualTo("summary ~ \"he said \\\"5\\\\\\\" wide\\\"\"");
    }

    @Test
    void escapesNewlinesWhichAreAParseErrorInsideAQuotedJqlString() {
        assertThat(JqlValue.quote("a\nb\tc")).isEqualTo("\"a\\nb\\tc\"");
    }

    @ParameterizedTest
    @CsvSource({
            "summary,summary",
            "Story Points,\"Story Points\"",
            "cf[10004],cf[10004]",
            "order,\"order\"",
            "status,status",
            "user,\"user\"",
    })
    void quotesAFieldNameOnlyWhenJqlRequiresIt(String raw, String expected) {
        assertThat(Jql.customFieldByName(raw).name()).isEqualTo(expected);
    }

    @Test
    void parenthesizesNestedCompositesSoPrecedenceIsNeverImplied() {
        JqlClause clause = Jql.project().is("OPS")
                .and(Jql.status().is("Open"))
                .or(Jql.assignee().isCurrentUser());

        assertThat(clause.render())
                .isEqualTo("(project = \"OPS\" AND status = \"Open\") OR assignee = currentUser()");
    }

    @Test
    void doesNotParenthesizeASingleClause() {
        assertThat(JqlClause.allOf(java.util.List.of(Jql.project().is("OPS"))).render())
                .isEqualTo("project = \"OPS\"");
    }

    @Test
    void rendersHistoryPredicatesInTheOrderJqlRequires() {
        String jql = Jql.status().changedTo("Done").byCurrentUser().after(LocalDate.of(2024, 1, 15)).render();

        assertThat(jql).isEqualTo("status CHANGED TO \"Done\" BY currentUser() AFTER \"2024-01-15\"");
    }

    @Test
    void rendersFunctionArgumentsEscaped() {
        assertThat(JqlFunctions.membersOf("ops \"team\"").render()).isEqualTo("membersOf(\"ops \\\"team\\\"\")");
    }

    @Test
    void rejectsAnEmptyInListBecauseJqlCannotExpressOneAndItWouldMatchNothing() {
        assertThatThrownBy(() -> Jql.project().in(java.util.List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one value");
    }

    @Test
    void reportsAQueryWithNoConditionAsUnbounded() {
        JqlQuery query = JqlQuery.builder().orderBy(Jql.created().desc()).build();

        assertThat(query.isUnbounded()).isTrue();
        assertThat(query.render()).isEqualTo("ORDER BY created DESC");
    }

    @Test
    void buildsRelativeAndAbsoluteDateClausesDifferently() {
        assertThat(Jql.created().between("-14d", "-7d").render())
                .isEqualTo("created >= \"-14d\" AND created <= \"-7d\"");
        assertThat(Jql.dueDate().onOrBefore(LocalDate.of(2026, 3, 1)).render())
                .isEqualTo("due <= \"2026-03-01\"");
    }

    @Test
    void addsAClauseOnlyWhenTheGuardHolds() {
        String jql = JqlQuery.builder()
                .where(Jql.project().is("OPS"))
                .whereIf(false, Jql.assignee().isCurrentUser())
                .render();

        assertThat(jql).isEqualTo("project = \"OPS\"");
    }
}
