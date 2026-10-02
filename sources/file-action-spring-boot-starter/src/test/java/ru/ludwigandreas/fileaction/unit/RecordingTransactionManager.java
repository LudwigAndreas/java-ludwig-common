package ru.ludwigandreas.fileaction.unit;

import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * A transaction manager that records what each transaction did, so a test can assert the boundary rather
 * than only the counts.
 *
 * <p>The boundary <em>is</em> the commit policy - see {@code ApplyPass} - so a test that only checked how many
 * rows were applied would pass for {@code PER_BATCH} and {@code PER_ROW} alike on a file with no rejects, and
 * would not notice the two being swapped. Recording commit and rollback is what makes the difference visible.
 */
class RecordingTransactionManager implements PlatformTransactionManager {

    /** What happened to each transaction, in order. */
    enum Outcome { COMMITTED, ROLLED_BACK }

    private final List<Outcome> outcomes = new ArrayList<>();

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus(true);
    }

    @Override
    public void commit(TransactionStatus status) {
        outcomes.add(status.isRollbackOnly() ? Outcome.ROLLED_BACK : Outcome.COMMITTED);
    }

    @Override
    public void rollback(TransactionStatus status) {
        outcomes.add(Outcome.ROLLED_BACK);
    }

    /** What happened to each transaction, in order. */
    List<Outcome> outcomes() {
        return List.copyOf(outcomes);
    }

    /** How many transactions were opened. */
    int transactionCount() {
        return outcomes.size();
    }

    /** How many committed. */
    long committed() {
        return outcomes.stream().filter(outcome -> outcome == Outcome.COMMITTED).count();
    }

    /** How many rolled back. */
    long rolledBack() {
        return outcomes.stream().filter(outcome -> outcome == Outcome.ROLLED_BACK).count();
    }
}
