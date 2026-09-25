package com.ledgerlite.instructions;

import com.ledgerlite.core.Instruction;
import com.ledgerlite.core.InstructionException;
import com.ledgerlite.core.LedgerContext;

import java.sql.SQLException;
import java.util.Set;

/** Withdraws {@code amount} from one account. */
public record DebitInstruction(String id, String accountId, long amount) implements Instruction {

    @Override
    public Set<String> accountIds() {
        return Set.of(accountId);
    }

    @Override
    public void apply(LedgerContext ctx) throws InstructionException, SQLException {
        ctx.debit(accountId, amount);
    }
}
