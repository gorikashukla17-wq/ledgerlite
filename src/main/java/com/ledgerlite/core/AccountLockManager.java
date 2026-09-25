package com.ledgerlite.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-account locks.
 *
 * <p>Instructions touching disjoint accounts run fully in parallel; instructions sharing
 * an account are serialised. Locks are always acquired in sorted account-id order, so
 * two transfers A→B and B→A can never deadlock each other.</p>
 */
public final class AccountLockManager {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Acquires all locks in global order and returns them in acquisition order. */
    public List<ReentrantLock> lockAll(Collection<String> accountIds) {
        List<ReentrantLock> acquired = new ArrayList<>();
        try {
            for (String id : new TreeSet<>(accountIds)) {
                ReentrantLock lock = locks.computeIfAbsent(id, k -> new ReentrantLock());
                lock.lock();
                acquired.add(lock);
            }
            return acquired;
        } catch (RuntimeException e) {
            unlockAll(acquired);
            throw e;
        }
    }

    /** Releases in reverse acquisition order. */
    public void unlockAll(List<ReentrantLock> acquired) {
        for (int i = acquired.size() - 1; i >= 0; i--) {
            acquired.get(i).unlock();
        }
    }
}
