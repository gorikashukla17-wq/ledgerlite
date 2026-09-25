package com.ledgerlite;

import com.ledgerlite.audit.Reconciler;
import com.ledgerlite.core.AccountService;
import com.ledgerlite.core.Instruction;
import com.ledgerlite.core.TransactionEngine;
import com.ledgerlite.db.Database;
import com.ledgerlite.instructions.DebitInstruction;
import com.ledgerlite.instructions.FeeInstruction;
import com.ledgerlite.instructions.TransferInstruction;
import com.ledgerlite.model.Account;
import com.ledgerlite.model.InstructionResult;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Demo: opens accounts, fires a burst of concurrent random transfers through the
 * engine, then proves (a) money was conserved and (b) the audit log reconciles.
 *
 * <p>Uses in-memory Derby unless LEDGERLITE_DB_URL points at MySQL.</p>
 */
public final class LedgerLiteApp {

    public static void main(String[] args) throws Exception {
        int accountsCount = 20;
        int instructionCount = args.length > 0 ? Integer.parseInt(args[0]) : 5_000;
        int threads = Runtime.getRuntime().availableProcessors() * 2;

        Database db = Database.fromEnvironment();
        db.initSchema();
        AccountService accounts = new AccountService(db);

        for (int i = 1; i <= accountsCount; i++) {
            accounts.open(accountId(i), "Customer " + i, 1_000_000L); // ₹10,000.00 in paise
        }
        accounts.open("FEES", "Fee income", 0L);
        long totalBefore = accounts.totalBalance();

        Random rnd = new Random(42);
        List<Instruction> batch = new ArrayList<>(instructionCount);
        for (int n = 0; n < instructionCount; n++) {
            String a = accountId(1 + rnd.nextInt(accountsCount));
            String b = accountId(1 + rnd.nextInt(accountsCount));
            int kind = rnd.nextInt(10);
            if (kind < 8 && !a.equals(b)) {
                batch.add(new TransferInstruction("TXN-" + n, a, b, 1 + rnd.nextInt(200_000)));
            } else if (kind == 8) {
                batch.add(new FeeInstruction("FEE-" + n, a, "FEES", 1 + rnd.nextInt(500_000), 25, 500));
            } else {
                // Withdrawals leave the system, so they are tracked separately below.
                batch.add(new DebitInstruction("WDL-" + n, a, 1 + rnd.nextInt(5_000)));
            }
        }

        long start = System.nanoTime();
        List<InstructionResult> results;
        try (TransactionEngine engine = new TransactionEngine(db, threads)) {
            results = engine.processAll(batch);
            // Re-submitting an already-processed instruction is a no-op.
            System.out.println("Replay of TXN-0 -> " + engine.process(batch.get(0)).status());
        }
        long millis = (System.nanoTime() - start) / 1_000_000;

        Map<InstructionResult.Status, Integer> byStatus = new EnumMap<>(InstructionResult.Status.class);
        long withdrawn = 0;
        for (int i = 0; i < results.size(); i++) {
            InstructionResult r = results.get(i);
            byStatus.merge(r.status(), 1, Integer::sum);
            if (r.isApplied() && batch.get(i) instanceof DebitInstruction d) {
                withdrawn += d.amount();
            }
        }

        long totalAfter = accounts.totalBalance();
        Reconciler.Report report = new Reconciler(db).reconcile();

        System.out.printf("Processed %,d instructions on %d threads in %,d ms%n", results.size(), threads, millis);
        System.out.println("Outcomes: " + byStatus);
        System.out.printf("Money before: %,d  after: %,d  withdrawn: %,d  -> conserved: %s%n",
                totalBefore, totalAfter, withdrawn, totalBefore == totalAfter + withdrawn);
        System.out.printf("Reconciliation: %d accounts, %,d audit entries replayed, %s%n",
                report.accountsChecked(), report.entriesReplayed(),
                report.isClean() ? "CLEAN" : report.discrepancies());
        for (Account a : accounts.all().subList(0, 5)) {
            System.out.printf("  %-6s %-12s %,12d%n", a.accountId(), a.ownerName(), a.balance());
        }
    }

    private static String accountId(int i) {
        return String.format("AC%04d", i);
    }
}
