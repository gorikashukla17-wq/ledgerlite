package com.ledgerlite;

import com.ledgerlite.audit.AuditLogRepository;
import com.ledgerlite.core.AccountService;
import com.ledgerlite.core.Instruction;
import com.ledgerlite.core.InstructionException;
import com.ledgerlite.core.LedgerContext;
import com.ledgerlite.core.TransactionEngine;
import com.ledgerlite.db.Database;
import com.ledgerlite.instructions.CreditInstruction;
import com.ledgerlite.instructions.DebitInstruction;
import com.ledgerlite.instructions.FeeInstruction;
import com.ledgerlite.instructions.TransferInstruction;
import com.ledgerlite.model.InstructionResult;
import com.ledgerlite.model.InstructionResult.Status;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TransactionEngineTest {

    private Database db;
    private AccountService accounts;
    private TransactionEngine engine;

    @Before
    public void setUp() throws Exception {
        db = TestDatabases.fresh();
        accounts = new AccountService(db);
        engine = new TransactionEngine(db, 4);
        accounts.open("A", "Alice", 10_000);
        accounts.open("B", "Bob", 5_000);
    }

    @After
    public void tearDown() {
        engine.close();
    }

    private long balance(String id) throws SQLException {
        return accounts.find(id).orElseThrow().balance();
    }

    private long auditCount() throws SQLException {
        try (Connection c = db.getConnection()) {
            return new AuditLogRepository().count(c);
        }
    }

    @Test
    public void transferMovesMoneyAndWritesTwoAuditEntries() throws Exception {
        long auditBefore = auditCount();
        InstructionResult r = engine.process(new TransferInstruction("T1", "A", "B", 2_500));

        assertEquals(Status.APPLIED, r.status());
        assertEquals(7_500, balance("A"));
        assertEquals(7_500, balance("B"));
        assertEquals(auditBefore + 2, auditCount());
    }

    @Test
    public void insufficientFundsIsRejectedAndChangesNothing() throws Exception {
        long auditBefore = auditCount();
        InstructionResult r = engine.process(new DebitInstruction("D1", "B", 5_001));

        assertEquals(Status.REJECTED, r.status());
        assertTrue(r.message().contains("Insufficient funds"));
        assertEquals(5_000, balance("B"));
        assertEquals(auditBefore, auditCount());
    }

    @Test
    public void failureAfterDebitRollsBackTheDebit() throws Exception {
        // Debit on A succeeds, then credit to a missing account fails: A must be untouched.
        long auditBefore = auditCount();
        InstructionResult r = engine.process(new TransferInstruction("T2", "A", "NOPE", 1_000));

        assertEquals(Status.REJECTED, r.status());
        assertEquals(10_000, balance("A"));
        assertEquals(auditBefore, auditCount());
    }

    @Test
    public void unexpectedRuntimeFailureMidInstructionRollsBack() throws Exception {
        Instruction explodes = new Instruction() {
            public String id() { return "BOOM"; }
            public Set<String> accountIds() { return Set.of("A"); }
            public void apply(LedgerContext ctx) throws InstructionException, SQLException {
                ctx.debit("A", 4_000);
                throw new IllegalStateException("simulated crash after debit");
            }
        };
        InstructionResult r = engine.process(explodes);

        assertEquals(Status.FAILED, r.status());
        assertEquals(10_000, balance("A"));
    }

    @Test
    public void duplicateInstructionIdIsNotAppliedTwice() throws Exception {
        CreditInstruction credit = new CreditInstruction("C1", "A", 100);
        assertEquals(Status.APPLIED, engine.process(credit).status());
        assertEquals(Status.DUPLICATE, engine.process(credit).status());
        assertEquals(10_100, balance("A"));
    }

    @Test
    public void rejectedInstructionIdCannotBeRetriedAsNew() throws Exception {
        DebitInstruction tooBig = new DebitInstruction("D2", "B", 999_999);
        assertEquals(Status.REJECTED, engine.process(tooBig).status());
        assertEquals(Status.DUPLICATE, engine.process(tooBig).status());
    }

    @Test
    public void nonPositiveAmountsAreRejected() throws Exception {
        assertEquals(Status.REJECTED, engine.process(new CreditInstruction("C0", "A", 0)).status());
        assertEquals(Status.REJECTED, engine.process(new DebitInstruction("Dneg", "A", -5)).status());
    }

    @Test
    public void undeclaredAccountAccessIsRefused() throws Exception {
        Instruction sneaky = new Instruction() {
            public String id() { return "SNEAKY"; }
            public Set<String> accountIds() { return Set.of("A"); }
            public void apply(LedgerContext ctx) throws InstructionException, SQLException {
                ctx.credit("B", 1); // B was not declared, so it was never locked
            }
        };
        assertEquals(Status.FAILED, engine.process(sneaky).status());
        assertEquals(5_000, balance("B"));
    }

    @Test
    public void feeInstructionUsesBasisPointsWithMinimum() throws Exception {
        accounts.open("FEES", "Fee income", 0);
        FeeInstruction pct = new FeeInstruction("F1", "A", "FEES", 400_000, 25, 50); // 0.25% of 4,000.00
        FeeInstruction min = new FeeInstruction("F2", "A", "FEES", 1_000, 25, 50);   // below minimum
        assertEquals(1_000, pct.feeAmount());
        assertEquals(50, min.feeAmount());

        engine.process(pct);
        engine.process(min);
        assertEquals(10_000 - 1_050, balance("A"));
        assertEquals(1_050, balance("FEES"));
    }
}
