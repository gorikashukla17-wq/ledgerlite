package com.ledgerlite.core;

/** A business-rule violation (insufficient funds, unknown account, ...). Causes rollback. */
public class InstructionException extends Exception {
    public InstructionException(String message) {
        super(message);
    }
}
