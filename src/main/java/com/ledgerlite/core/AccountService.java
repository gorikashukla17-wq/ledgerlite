package com.ledgerlite.core;

import com.ledgerlite.audit.AuditLogRepository;
import com.ledgerlite.db.AccountRepository;
import com.ledgerlite.db.Database;
import com.ledgerlite.model.Account;
import com.ledgerlite.model.AuditEntry.OpType;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Opening accounts and read-only queries. */
public final class AccountService {

    private final Database database;
    private final AccountRepository accounts = new AccountRepository();
    private final AuditLogRepository audit = new AuditLogRepository();

    public AccountService(Database database) {
        this.database = database;
    }

    /** Creates the account and its OPEN audit entry atomically. */
    public Account open(String accountId, String ownerName, long openingBalance) throws SQLException {
        if (openingBalance < 0) {
            throw new IllegalArgumentException("Opening balance cannot be negative");
        }
        try (Connection conn = database.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Account account = new Account(accountId, ownerName, openingBalance);
                accounts.insert(conn, account);
                audit.append(conn, accountId, "OPEN-" + accountId, OpType.OPEN,
                        openingBalance, 0L, openingBalance);
                conn.commit();
                return account;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public Optional<Account> find(String accountId) throws SQLException {
        try (Connection conn = database.getConnection()) {
            return accounts.find(conn, accountId);
        }
    }

    public List<Account> all() throws SQLException {
        try (Connection conn = database.getConnection()) {
            return accounts.findAll(conn);
        }
    }

    public long totalBalance() throws SQLException {
        return all().stream().mapToLong(Account::balance).sum();
    }
}
