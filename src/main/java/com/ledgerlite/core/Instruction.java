package com.ledgerlite.core;

import java.sql.SQLException;
import java.util.Set;

/**
 * A unit of work against the ledger.
 *
 * <p>This is the extension point of the engine: a new instruction type (fees, interest,
 * reversals, ...) is a new implementation of this interface. The processing loop in
 * {@link TransactionEngine} never changes.</p>
 */
public interface Instruction {

    /** Globally unique id; used for idempotency and written to every audit entry. */
    String id();

    /**
     * Every account this instruction may read or write. The engine locks exactly these
     * accounts (in a fixed global order) before calling {@link #apply}.
     */
    Set<String> accountIds();

    /**
     * Performs the instruction through the given context. Throwing
     * {@link InstructionException} rolls back everything done so far.
     */
    void apply(LedgerContext ctx) throws InstructionException, SQLException;
}
