package com.ledgerlite;

import com.ledgerlite.audit.AuditLogRepository;
import com.ledgerlite.audit.Reconciler;
import com.ledgerlite.core.AccountService;
import com.ledgerlite.core.TransactionEngine;
import com.ledgerlite.db.Database;
import com.ledgerlite.instructions.CreditInstruction;
import com.ledgerlite.instructions.DebitInstruction;
import com.ledgerlite.model.AuditEntry;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReconcilerTest {

    @Test
    public void cleanLedgerReconciles() throws Exception {
        Database db = TestDatabases.fresh();
        new AccountService(db).open("A", "Alice", 1_000);
        try (TransactionEngine engine = new TransactionEngine(db, 2)) {
            engine.process(new CreditInstruction("c1", "A", 500));
            engine.process(new DebitInstruction("d1", "A", 200));
        }
        Reconciler.Report report = new Reconciler(db).reconcile();
        assertTrue(report.isClean());
        assertEquals(3, report.entriesReplayed());
    }

    @Test
    public void tamperedBalanceIsDetected() throws Exception {
        Database db = TestDatabases.fresh();
        new AccountService(db).open("A", "Alice", 1_000);

        // Someone edits the balance directly, bypassing the engine.
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE accounts SET balance = 9999 WHERE account_id = 'A'")) {
            ps.executeUpdate();
        }
        Reconciler.Report report = new Reconciler(db).reconcile();
        assertFalse(report.isClean());
        assertEquals("A", report.discrepancies().get(0).accountId());
    }

    @Test
    public void pointInTimeBalanceReplaysHistory() throws Exception {
        Database db = TestDatabases.fresh();
        new AccountService(db).open("A", "Alice", 1_000);
        try (TransactionEngine engine = new TransactionEngine(db, 1)) {
            engine.process(new CreditInstruction("c1", "A", 500));
            engine.process(new DebitInstruction("d1", "A", 300));
        }
        List<AuditEntry> history;
        try (Connection c = db.getConnection()) {
            history = new AuditLogRepository().findByAccount(c, "A");
        }
        Reconciler reconciler = new Reconciler(db);
        assertEquals(1_000, reconciler.balanceAsOf("A", history.get(0).entryId()));
        assertEquals(1_500, reconciler.balanceAsOf("A", history.get(1).entryId()));
        assertEquals(1_200, reconciler.balanceAsOf("A", history.get(2).entryId()));
        assertEquals("d1", history.get(2).instructionId());
    }
}
