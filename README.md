# LedgerLite — Concurrent Transaction Processing Engine

![Java 17](https://img.shields.io/badge/Java-17-orange) ![MySQL 8](https://img.shields.io/badge/MySQL-8-blue) ![Tests](https://img.shields.io/badge/tests-16%20passing-brightgreen) ![License: MIT](https://img.shields.io/badge/license-MIT-green)

A core-Java engine that applies debit / credit / transfer instructions to account balances
**in parallel**, without ever leaving the ledger in a partial state, and with an
**append-only audit log** that can replay and reconcile every balance.

**Stack:** Java 17 · Multithreading (`ExecutorService`, `ReentrantLock`) · JDBC · MySQL 8 (Apache Derby embedded for demo/tests) · JUnit 4 · Maven

## Highlights

- Processes **5,000 instructions in parallel** on a thread pool with no lost updates and no deadlocks.
- Every instruction is **all-or-nothing** (one JDBC transaction) and runs **exactly once**.
- An **append-only audit log** can rebuild and verify every account balance.
- New instruction types plug in **without changing the engine**.

## Contents

[What it does](#what-it-does) · [Architecture](#architecture) · [Project layout](#project-layout) · [Running it](#running-it) · [Tests](#tests-that-guard-concurrency) · [Design notes](#design-notes--limits) · [Author](#author) · [License](#license)

---

## What it does

| Guarantee | How it is achieved |
|---|---|
| **Atomicity**: an instruction either fully applies or leaves nothing behind | Each instruction runs inside one JDBC transaction (`autoCommit=false`). Any exception rolls back, e.g. a transfer whose debit succeeded but whose credit target doesn't exist. |
| **Parallelism without lost updates** | Fixed thread pool + **per-account locks**. Transfers touching different accounts run concurrently; transfers sharing an account are serialised. |
| **No deadlocks** | Locks are always acquired in sorted account-id order, so `A→B` and `B→A` can't wait on each other. |
| **Exactly-once processing** | `processed_instructions` table keyed by instruction id. A re-submitted id returns `DUPLICATE` and changes nothing. |
| **Full audit trail** | Every balance change writes an `audit_log` row (`balance_before`, `balance_after`, instruction id, timestamp) **in the same transaction**. The log is insert-only. |
| **Replay & reconciliation** | `Reconciler` rebuilds every balance from the audit log alone, checks the before/after chain, and flags any account whose stored balance the log cannot explain. It also gives point-in-time balances. |
| **Exact money** | Amounts are `long` minor units (paise), never `double`. |

## Architecture

```
          submit(Instruction)
                 │
        ┌────────▼─────────┐   fixed thread pool
        │ TransactionEngine│───────────────────────────┐
        └────────┬─────────┘                           │
     lock accounts (sorted)  ◄── AccountLockManager    │
                 │                                     │
     BEGIN (JDBC, autoCommit=false)                    │
     idempotency check  ─── processed_instructions     │
     instruction.apply(LedgerContext)                  │
        ├─ ctx.debit()  ─┬─ accounts   (UPDATE)        │
        └─ ctx.credit() ─┴─ audit_log  (INSERT only)   │
     COMMIT  / ROLLBACK on any exception               │
     unlock ◄──────────────────────────────────────────┘
```

### Extending: new instruction types without touching the engine

`Instruction` is the only extension point:

```java
public interface Instruction {
    String id();
    Set<String> accountIds();                 // what to lock
    void apply(LedgerContext ctx) throws InstructionException, SQLException;
}
```

`DebitInstruction`, `CreditInstruction` and `TransferInstruction` are ~15 lines each.
`FeeInstruction` (percentage fee in basis points with a minimum) was added later **with zero
changes to `TransactionEngine`** — that's the open/closed principle in practice.
`LedgerContext` refuses access to any account an instruction did not declare, so a buggy
instruction can't bypass the locking.

## Project layout

```
src/main/java/com/ledgerlite/
  LedgerLiteApp.java            demo: 5,000 concurrent instructions + reconciliation
  core/   TransactionEngine, AccountLockManager, LedgerContext, Instruction, AccountService
  instructions/  Debit, Credit, Transfer, Fee
  audit/  AuditLogRepository (append-only), Reconciler
  db/     Database (JDBC + schema bootstrap), AccountRepository
  model/  Account, AuditEntry, InstructionResult
src/main/resources/
  schema-mysql.sql              production schema (InnoDB, CHECK balance >= 0, FKs, indexes)
  schema-derby.sql              same model, embedded dialect
src/test/java/com/ledgerlite/
  TransactionEngineTest         rollback, idempotency, validation, extension
  ConcurrencyTest               lost-update, overdraw, conservation, deadlock tests
  ReconcilerTest                clean replay, tamper detection, point-in-time balance
```

## Running it

Requires JDK 17+ and Maven.

```bash
mvn test                 # 16 tests
mvn -q compile exec:java # demo on an in-memory database
```

Sample demo output:

```
Processed 5,000 instructions on 4 threads in 4,004 ms
Outcomes: {APPLIED=4746, REJECTED=254}
Money before: 20,000,000  after: 18,263,472  withdrawn: 1,736,528  -> conserved: true
Reconciliation: 21 accounts, 8,817 audit entries replayed, CLEAN
```

(`REJECTED` = insufficient funds; those instructions were rolled back cleanly.)

### Against MySQL

```bash
docker compose up -d
export LEDGERLITE_DB_URL="jdbc:mysql://localhost:3306/ledgerlite"
export LEDGERLITE_DB_USER=ledger LEDGERLITE_DB_PASSWORD=ledger
mvn -q compile exec:java
```

The schema is created automatically on first run.

## Tests that guard concurrency

- **`concurrentCreditsToOneAccountLoseNothing`**: 1,000 parallel `+1` credits to one account on 16 threads must end at exactly 1,000. Any lost update fails the test.
- **`concurrentDebitsNeverOverdraw`**: 1,000 parallel `-1` debits against a balance of 500: exactly 500 may succeed.
- **`randomTransfersConserveMoneyAndReconcile`**: 3,000 random transfers; total money unchanged and the audit log reconciles clean.
- **`opposingTransfersDoNotDeadlock`**: 1,000 alternating `X→Y` / `Y→X` transfers finish within the timeout.

## Design notes & limits

- Locks are in-process, which is correct for a single engine instance. Running several
  instances against one database would add `SELECT … FOR UPDATE` row locks (or optimistic
  versioning) so the database itself arbitrates.
- `READ_COMMITTED` isolation is enough here because the engine already serialises writers
  per account. The `CHECK (balance >= 0)` constraint is a last line of defence in MySQL.
- Rejections are recorded in a separate short transaction *after* the rollback, so the
  outcome is persisted even though the instruction's own changes are discarded.

## Author

**Gorika Shukla** · GitHub [@gorikashukla17-wq](https://github.com/gorikashukla17-wq)

## License

Released under the [MIT License](LICENSE).
