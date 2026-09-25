package com.ledgerlite.audit;

import com.ledgerlite.model.AuditEntry;
import com.ledgerlite.model.AuditEntry.OpType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only access to the audit log. There is intentionally no update or delete
 * method: history can only grow.
 */
public final class AuditLogRepository {

    public void append(Connection conn, String accountId, String instructionId, OpType opType,
                       long amount, long balanceBefore, long balanceAfter) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO audit_log (account_id, instruction_id, op_type, amount, balance_before, balance_after) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, accountId);
            ps.setString(2, instructionId);
            ps.setString(3, opType.name());
            ps.setLong(4, amount);
            ps.setLong(5, balanceBefore);
            ps.setLong(6, balanceAfter);
            ps.executeUpdate();
        }
    }

    /** Entries for one account in the order they were written. */
    public List<AuditEntry> findByAccount(Connection conn, String accountId) throws SQLException {
        List<AuditEntry> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT entry_id, account_id, instruction_id, op_type, amount, balance_before, balance_after, created_at "
                        + "FROM audit_log WHERE account_id = ? ORDER BY entry_id")) {
            ps.setString(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AuditEntry(rs.getLong(1), rs.getString(2), rs.getString(3),
                            OpType.valueOf(rs.getString(4)), rs.getLong(5), rs.getLong(6), rs.getLong(7),
                            rs.getTimestamp(8)));
                }
            }
        }
        return out;
    }

    public long count(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM audit_log");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
