package com.ledgerlite;

import com.ledgerlite.db.Database;

import java.util.concurrent.atomic.AtomicInteger;

/** Gives every test its own fresh in-memory database. */
final class TestDatabases {
    private static final AtomicInteger SEQ = new AtomicInteger();

    private TestDatabases() { }

    static Database fresh() {
        Database db = Database.inMemory("test" + SEQ.incrementAndGet() + "_" + System.nanoTime());
        db.initSchema();
        return db;
    }
}
