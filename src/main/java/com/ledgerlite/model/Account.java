package com.ledgerlite.model;

/**
 * Immutable snapshot of an account row. Balances are held in minor units
 * (e.g. paise) as {@code long} so arithmetic is exact.
 */
public record Account(String accountId, String ownerName, long balance) {
}
