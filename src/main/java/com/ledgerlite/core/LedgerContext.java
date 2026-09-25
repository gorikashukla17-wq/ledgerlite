package com.ledgerlite.core;

import com.ledgerlite.audit.AuditLogRepository;
import com.ledgerlite.db.AccountRepository;
import com.ledgerlite.model.Account;
import com.ledgerlite.model.AuditEntry.OpType;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/**
 * The only way an {@link Instruction} can touch balances. Every balance change goes
 * through {@link #debit} or {@link #credit}, which always writes the matching audit
 * entry in the same database transaction — so the ledger and its audit trail can
 * never disagree.
 */
public final class LedgerContext {

    private final Connection conn;
    private final String instructionId;
    private final Set<String> lockedAccounts;
    private final AccountRepository accounts;
    private final AuditLogRepository audit;

    LedgerContext(Connection conn, String instructionId, Set<String> lockedAccounts,
                  AccountRepository accounts, AuditLogRepository audit) {
        this.conn = conn;
        this.instructionId = instructionId;
        this.lockedAccounts = lockedAccounts;
        this.accounts = accounts;
        this.audit = audit;
    }

    public long balanceOf(String accountId) throws InstructionException, SQLException {
        return load(accountId).balance();
    }

    public void debit(String accountId, long amount) throws InstructionException, SQLException {
        requirePositive(amount);
        Account account = load(accountId);
        if (account.balance() < amount) {
            throw new InstructionException("Insufficient funds in " + accountId
                    + ": balance " + account.balance() + ", requested " + amount);
        }
        long after = account.balance() - amount;
        accounts.updateBalance(conn, accountId, after);
        audit.append(conn, accountId, instructionId, OpType.DEBIT, amount, account.balance(), after);
    }

    public void credit(String accountId, long amount) throws InstructionException, SQLException {
        requirePositive(amount);
        Account account = load(accountId);
        long after = Math.addExact(account.balance(), amount);
        accounts.updateBalance(conn, accountId, after);
        audit.append(conn, accountId, instructionId, OpType.CREDIT, amount, account.balance(), after);
    }

    private Account load(String accountId) throws InstructionException, SQLException {
        if (!lockedAccounts.contains(accountId)) {
            // Guards against an Instruction under-declaring accountIds(), which would bypass locking.
            throw new IllegalStateException("Instruction " + instructionId
                    + " touched account " + accountId + " without declaring it");
        }
        return accounts.find(conn, accountId)
                .orElseThrow(() -> new InstructionException("Unknown account " + accountId));
    }

    private static void requirePositive(long amount) throws InstructionException {
        if (amount <= 0) {
            throw new InstructionException("Amount must be positive, got " + amount);
        }
    }
}
