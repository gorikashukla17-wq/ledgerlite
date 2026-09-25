package com.ledgerlite.instructions;

import com.ledgerlite.core.Instruction;
import com.ledgerlite.core.InstructionException;
import com.ledgerlite.core.LedgerContext;

import java.sql.SQLException;
import java.util.Set;

/**
 * Moves {@code amount} from one account to another. The debit is applied first; if the
 * credit then fails (e.g. unknown target) the engine rolls back the debit too.
 */
public record TransferInstruction(String id, String fromAccount, String toAccount, long amount)
        implements Instruction {

    @Override
    public Set<String> accountIds() {
        return Set.of(fromAccount, toAccount);
    }

    @Override
    public void apply(LedgerContext ctx) throws InstructionException, SQLException {
        if (fromAccount.equals(toAccount)) {
            throw new InstructionException("Cannot transfer to the same account");
        }
        ctx.debit(fromAccount, amount);
        ctx.credit(toAccount, amount);
    }
}
