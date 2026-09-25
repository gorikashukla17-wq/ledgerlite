package com.ledgerlite.core;

import com.ledgerlite.audit.AuditLogRepository;
import com.ledgerlite.db.AccountRepository;
import com.ledgerlite.db.Database;
import com.ledgerlite.model.InstructionResult;
import com.ledgerlite.model.InstructionResult.Status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Processes {@link Instruction}s on a fixed thread pool.
 *
 * <p>For each instruction the engine:</p>
 * <ol>
 *   <li>locks the instruction's accounts (sorted order, see {@link AccountLockManager});</li>
 *   <li>opens a JDBC transaction ({@code autoCommit=false});</li>
 *   <li>rejects the instruction if its id was already processed (idempotency);</li>
 *   <li>runs {@link Instruction#apply}, which writes balances and audit rows;</li>
 *   <li>commits — or rolls back on any exception, so no partial state is ever visible.</li>
 * </ol>
 */
public final class TransactionEngine implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(TransactionEngine.class.getName());

    private final Database database;
    private final ExecutorService pool;
    private final AccountLockManager lockManager = new AccountLockManager();
    private final AccountRepository accounts = new AccountRepository();
    private final AuditLogRepository audit = new AuditLogRepository();

    public TransactionEngine(Database database, int threads) {
        this.database = database;
        AtomicInteger counter = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "ledger-worker-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** Asynchronously processes one instruction. */
    public CompletableFuture<InstructionResult> submit(Instruction instruction) {
        return CompletableFuture.supplyAsync(() -> process(instruction), pool);
    }

    /** Submits a batch and waits for all of it; results are in submission order. */
    public List<InstructionResult> processAll(List<? extends Instruction> instructions) {
        List<CompletableFuture<InstructionResult>> futures = new ArrayList<>(instructions.size());
        for (Instruction i : instructions) {
            futures.add(submit(i));
        }
        List<InstructionResult> results = new ArrayList<>(futures.size());
        for (CompletableFuture<InstructionResult> f : futures) {
            results.add(f.join());
        }
        return results;
    }

    /** Synchronously processes one instruction on the calling thread. */
    public InstructionResult process(Instruction instruction) {
        Set<String> accountIds = instruction.accountIds();
        List<ReentrantLock> held = lockManager.lockAll(accountIds);
        try {
            return processLocked(instruction, accountIds);
        } finally {
            lockManager.unlockAll(held);
        }
    }

    private InstructionResult processLocked(Instruction instruction, Set<String> accountIds) {
        String id = instruction.id();
        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(false);
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                if (alreadyProcessed(conn, id)) {
                    conn.rollback();
                    return new InstructionResult(id, Status.DUPLICATE, "Instruction already processed");
                }
                instruction.apply(new LedgerContext(conn, id, accountIds, accounts, audit));
                recordOutcome(conn, id, Status.APPLIED, null);
                conn.commit();
                return new InstructionResult(id, Status.APPLIED, "OK");
            } catch (InstructionException e) {
                conn.rollback();
                recordRejection(id, e.getMessage());
                return new InstructionResult(id, Status.REJECTED, e.getMessage());
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                if (isDuplicateKey(e)) {
                    // Lost a race with another submission of the same id.
                    return new InstructionResult(id, Status.DUPLICATE, "Instruction already processed");
                }
                LOG.log(Level.WARNING, "Instruction " + id + " failed and was rolled back", e);
                return new InstructionResult(id, Status.FAILED, e.getMessage());
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Could not obtain connection for " + id, e);
            return new InstructionResult(id, Status.FAILED, e.getMessage());
        }
    }

    private boolean alreadyProcessed(Connection conn, String id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM processed_instructions WHERE instruction_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void recordOutcome(Connection conn, String id, Status status, String message) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO processed_instructions (instruction_id, status, message) VALUES (?, ?, ?)")) {
            ps.setString(1, id);
            ps.setString(2, status.name());
            ps.setString(3, message == null ? null : truncate(message, 255));
            ps.executeUpdate();
        }
    }

    /** Rejections are recorded in their own short transaction, after the rollback. */
    private void recordRejection(String id, String message) {
        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(true);
            recordOutcome(conn, id, Status.REJECTED, message);
        } catch (SQLException e) {
            if (!isDuplicateKey(e)) {
                LOG.log(Level.WARNING, "Could not record rejection of " + id, e);
            }
        }
    }

    private static boolean isDuplicateKey(Exception e) {
        // 23xxx = integrity constraint violation (both MySQL and Derby)
        return e instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("23");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
