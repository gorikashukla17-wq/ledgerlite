package com.ledgerlite.audit;

import com.ledgerlite.db.AccountRepository;
import com.ledgerlite.db.Database;
import com.ledgerlite.model.Account;
import com.ledgerlite.model.AuditEntry;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds every balance purely from the audit log and compares it with the
 * {@code accounts} table. Any mismatch — a broken before/after chain, or a stored
 * balance that the log cannot explain — is reported as a {@link Discrepancy}.
 */
public final class Reconciler {

    public record Discrepancy(String accountId, String reason) { }

    public record Report(int accountsChecked, long entriesReplayed, List<Discrepancy> discrepancies) {
        public boolean isClean() {
            return discrepancies.isEmpty();
        }
    }

    private final Database database;
    private final AccountRepository accounts = new AccountRepository();
    private final AuditLogRepository audit = new AuditLogRepository();

    public Reconciler(Database database) {
        this.database = database;
    }

    public Report reconcile() throws SQLException {
        List<Discrepancy> problems = new ArrayList<>();
        long replayed = 0;
        try (Connection conn = database.getConnection()) {
            List<Account> all = accounts.findAll(conn);
            for (Account account : all) {
                List<AuditEntry> entries = audit.findByAccount(conn, account.accountId());
                replayed += entries.size();
                long replayedBalance = replay(account.accountId(), entries, Long.MAX_VALUE, problems);
                if (replayedBalance != account.balance()) {
                    problems.add(new Discrepancy(account.accountId(),
                            "Stored balance " + account.balance() + " but audit log replays to " + replayedBalance));
                }
            }
            return new Report(all.size(), replayed, problems);
        }
    }

    /** Point-in-time balance: replays entries up to and including {@code upToEntryId}. */
    public long balanceAsOf(String accountId, long upToEntryId) throws SQLException {
        try (Connection conn = database.getConnection()) {
            return replay(accountId, audit.findByAccount(conn, accountId), upToEntryId, new ArrayList<>());
        }
    }

    private static long replay(String accountId, List<AuditEntry> entries, long upToEntryId,
                               List<Discrepancy> problems) {
        long running = 0;
        for (AuditEntry e : entries) {
            if (e.entryId() > upToEntryId) {
                break;
            }
            if (e.balanceBefore() != running) {
                problems.add(new Discrepancy(accountId, "Chain broken at entry " + e.entryId()
                        + ": expected balance_before " + running + ", found " + e.balanceBefore()));
            }
            running = switch (e.opType()) {
                case OPEN, CREDIT -> running + e.amount();
                case DEBIT -> running - e.amount();
            };
            if (running != e.balanceAfter()) {
                problems.add(new Discrepancy(accountId, "Entry " + e.entryId()
                        + " records balance_after " + e.balanceAfter() + " but arithmetic gives " + running));
            }
        }
        return running;
    }
}
