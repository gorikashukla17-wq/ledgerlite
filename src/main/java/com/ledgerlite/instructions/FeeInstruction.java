package com.ledgerlite.instructions;

import com.ledgerlite.core.Instruction;
import com.ledgerlite.core.InstructionException;
import com.ledgerlite.core.LedgerContext;

import java.sql.SQLException;
import java.util.Set;

/**
 * Charges a percentage fee (in basis points, minimum {@code minFee}) from a customer account
 * into a fee-income account.
 *
 * <p>Included to show the extension point: this type was added without touching
 * {@link com.ledgerlite.core.TransactionEngine}.</p>
 */
public record FeeInstruction(String id, String customerAccount, String feeAccount,
                             long referenceAmount, int basisPoints, long minFee) implements Instruction {

    @Override
    public Set<String> accountIds() {
        return Set.of(customerAccount, feeAccount);
    }

    public long feeAmount() {
        return Math.max(minFee, Math.multiplyExact(referenceAmount, (long) basisPoints) / 10_000L);
    }

    @Override
    public void apply(LedgerContext ctx) throws InstructionException, SQLException {
        long fee = feeAmount();
        ctx.debit(customerAccount, fee);
        ctx.credit(feeAccount, fee);
    }
}
