package com.aigreentick.migration.core;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Transaction shapes used by the steps.
 * <ul>
 *   <li>{@link #inTx} — REQUIRED: a batch commits as one unit (joins the outer dry-run transaction if any).</li>
 *   <li>{@link #row} — NESTED (savepoint): one old row and its children; a failure rolls back only this row,
 *       is written to mig_errors, and the step continues. Without an outer transaction it is its own transaction.</li>
 *   <li>{@link #requiresNew} — separate connection: problem log, run bookkeeping (survive a dry-run rollback).</li>
 * </ul>
 */
@Component
public class Tx {
    private final TransactionTemplate required;
    private final TransactionTemplate nested;
    private final TransactionTemplate requiresNew;
    private final IdMap idMap;

    public Tx(PlatformTransactionManager tm, IdMap idMap) {
        this.required = new TransactionTemplate(tm);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.nested = new TransactionTemplate(tm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.idMap = idMap;
    }

    public void inTx(Runnable r) {
        required.execute(s -> { r.run(); return null; });
    }

    public <T> T inTx(Supplier<T> r) {
        return required.execute(s -> r.get());
    }

    /** The whole run inside one transaction that is always rolled back (dry run). */
    public void rollbackOnly(Runnable r) {
        required.execute(s -> {
            s.setRollbackOnly();
            r.run();
            return null;
        });
    }

    /** savepoint (or own transaction) returning a value; a failure rolls back only this unit and is rethrown */
    public <T> T nested(Supplier<T> r) {
        return nested.execute(s -> r.get());
    }

    public void requiresNew(Runnable r) {
        requiresNew.execute(s -> { r.run(); return null; });
    }

    /**
     * Runs one row unit in a savepoint. Returns true when it committed (to the savepoint / own transaction).
     * {@link SkipRow} = the unit decided not to migrate the row (already logged), rolled back quietly.
     * A {@link DataAccessException} (constraint, ENUM, length, ...) is rolled back and logged as ERROR.
     */
    public boolean row(StepContext ctx, String sourceTable, Long oldId, Runnable unit) {
        idMap.beginUnit();
        try {
            nested.execute(s -> { unit.run(); return null; });
            idMap.commitUnit();
            return true;
        } catch (SkipRow e) {
            idMap.rollbackUnit();
            return false;
        } catch (DataAccessException e) {
            idMap.rollbackUnit();
            ctx.problems().error(sourceTable, oldId, "SQL_ERROR", rootMessage(e));
            ctx.stats().current().errors++;
            return false;
        } catch (RuntimeException e) {
            idMap.rollbackUnit();
            throw e;
        }
    }

    public static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }

    /** Thrown inside a row unit to abandon the row (after logging why). */
    public static final class SkipRow extends RuntimeException {
        public SkipRow() { super(null, null, false, false); }
    }
}
