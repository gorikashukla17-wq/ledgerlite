package com.ledgerlite;

import com.ledgerlite.audit.Reconciler;
import com.ledgerlite.core.AccountService;
import com.ledgerlite.core.Instruction;
import com.ledgerlite.core.TransactionEngine;
import com.ledgerlite.db.Database;
import com.ledgerlite.instructions.CreditInstruction;
import com.ledgerlite.instructions.DebitInstruction;
import com.ledgerlite.instructions.TransferInstruction;
import com.ledgerlite.model.InstructionResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * These tests fail if the engine ever loses an update (two threads reading the same
 * balance and both writing back) or deadlocks on opposing transfers.
 */
public class ConcurrencyTest {

    @Test(timeout = 60_000)
    public void concurrentCreditsToOneAccountLoseNothing() throws Exception {
        Database db = TestDatabases.fresh();
        AccountService accounts = new AccountService(db);
        accounts.open("HOT", "Hot account", 0);

        int n = 1_000;
        List<Instruction> credits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            credits.add(new CreditInstruction("C" + i, "HOT", 1));
        }
        try (TransactionEngine engine = new TransactionEngine(db, 16)) {
            engine.processAll(credits).forEach(r -> assertTrue(r.toString(), r.isApplied()));
        }
        assertEquals(n, accounts.find("HOT").orElseThrow().balance());
    }

    @Test(timeout = 60_000)
    public void concurrentDebitsNeverOverdraw() throws Exception {
        Database db = TestDatabases.fresh();
        AccountService accounts = new AccountService(db);
        accounts.open("A", "Alice", 500);

        List<Instruction> debits = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            debits.add(new DebitInstruction("D" + i, "A", 1));
        }
        long applied;
        try (TransactionEngine engine = new TransactionEngine(db, 16)) {
            applied = engine.processAll(debits).stream().filter(InstructionResult::isApplied).count();
        }
        assertEquals("exactly the available balance can be withdrawn", 500, applied);
        assertEquals(0, accounts.find("A").orElseThrow().balance());
    }

    @Test(timeout = 120_000)
    public void randomTransfersConserveMoneyAndReconcile() throws Exception {
        Database db = TestDatabases.fresh();
        AccountService accounts = new AccountService(db);
        int accountCount = 8;
        for (int i = 0; i < accountCount; i++) {
            accounts.open("AC" + i, "Owner " + i, 100_000);
        }
        long before = accounts.totalBalance();

        Random rnd = new Random(7);
        List<Instruction> transfers = new ArrayList<>();
        for (int i = 0; i < 3_000; i++) {
            int a = rnd.nextInt(accountCount);
            int b = (a + 1 + rnd.nextInt(accountCount - 1)) % accountCount; // always different
            transfers.add(new TransferInstruction("T" + i, "AC" + a, "AC" + b, 1 + rnd.nextInt(30_000)));
        }
        try (TransactionEngine engine = new TransactionEngine(db, 16)) {
            engine.processAll(transfers);
        }

        assertEquals("transfers must neither create nor destroy money", before, accounts.totalBalance());
        Reconciler.Report report = new Reconciler(db).reconcile();
        assertTrue(report.discrepancies().toString(), report.isClean());
    }

    @Test(timeout = 60_000)
    public void opposingTransfersDoNotDeadlock() throws Exception {
        Database db = TestDatabases.fresh();
        AccountService accounts = new AccountService(db);
        accounts.open("X", "X", 1_000_000);
        accounts.open("Y", "Y", 1_000_000);

        List<Instruction> pingPong = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            pingPong.add(i % 2 == 0
                    ? new TransferInstruction("P" + i, "X", "Y", 10)
                    : new TransferInstruction("P" + i, "Y", "X", 10));
        }
        try (TransactionEngine engine = new TransactionEngine(db, 16)) {
            engine.processAll(pingPong).forEach(r -> assertTrue(r.toString(), r.isApplied()));
        }
        assertEquals(1_000_000, accounts.find("X").orElseThrow().balance());
        assertEquals(1_000_000, accounts.find("Y").orElseThrow().balance());
    }
}
