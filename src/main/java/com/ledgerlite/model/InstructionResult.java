package com.ledgerlite.model;

/** Outcome of processing a single instruction. */
public record InstructionResult(String instructionId, Status status, String message) {

    public enum Status {
        /** All changes committed. */
        APPLIED,
        /** Business rule failed; every change from this instruction was rolled back. */
        REJECTED,
        /** This instruction id was already processed; nothing was done. */
        DUPLICATE,
        /** Infrastructure failure (e.g. database unavailable); rolled back. */
        FAILED
    }

    public boolean isApplied() {
        return status == Status.APPLIED;
    }
}
