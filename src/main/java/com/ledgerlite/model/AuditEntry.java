package com.ledgerlite.model;

import java.sql.Timestamp;

/** One immutable row of the append-only audit log. */
public record AuditEntry(long entryId,
                         String accountId,
                         String instructionId,
                         OpType opType,
                         long amount,
                         long balanceBefore,
                         long balanceAfter,
                         Timestamp createdAt) {

    public enum OpType { OPEN, DEBIT, CREDIT }
}
