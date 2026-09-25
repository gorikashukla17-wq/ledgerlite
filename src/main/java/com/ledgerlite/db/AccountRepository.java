package com.ledgerlite.db;

import com.ledgerlite.model.Account;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC data access for the {@code accounts} table. Every method takes the caller's
 * {@link Connection} so it participates in the caller's transaction.
 */
public final class AccountRepository {

    public void insert(Connection conn, Account account) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO accounts (account_id, owner_name, balance) VALUES (?, ?, ?)")) {
            ps.setString(1, account.accountId());
            ps.setString(2, account.ownerName());
            ps.setLong(3, account.balance());
            ps.executeUpdate();
        }
    }

    public Optional<Account> find(Connection conn, String accountId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT account_id, owner_name, balance FROM accounts WHERE account_id = ?")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Account(rs.getString(1), rs.getString(2), rs.getLong(3)));
            }
        }
    }

    public void updateBalance(Connection conn, String accountId, long newBalance) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE accounts SET balance = ? WHERE account_id = ?")) {
            ps.setLong(1, newBalance);
            ps.setString(2, accountId);
            if (ps.executeUpdate() != 1) {
                throw new SQLException("Account vanished during update: " + accountId);
            }
        }
    }

    public List<Account> findAll(Connection conn) throws SQLException {
        List<Account> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT account_id, owner_name, balance FROM accounts ORDER BY account_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Account(rs.getString(1), rs.getString(2), rs.getLong(3)));
            }
        }
        return out;
    }
}
