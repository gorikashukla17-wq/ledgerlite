-- LedgerLite schema for MySQL 8 (InnoDB gives row-level locking and ACID transactions)

CREATE TABLE IF NOT EXISTS accounts (
    account_id   VARCHAR(32)  NOT NULL PRIMARY KEY,
    owner_name   VARCHAR(100) NOT NULL,
    balance      BIGINT       NOT NULL,           -- minor units (paise), never floating point
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
) ENGINE = InnoDB;

-- Append-only: the application only ever INSERTs into this table.
CREATE TABLE IF NOT EXISTS audit_log (
    entry_id        BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    account_id      VARCHAR(32)  NOT NULL,
    instruction_id  VARCHAR(64)  NOT NULL,
    op_type         VARCHAR(20)  NOT NULL,        -- OPEN / DEBIT / CREDIT
    amount          BIGINT       NOT NULL,
    balance_before  BIGINT       NOT NULL,
    balance_after   BIGINT       NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_audit_account FOREIGN KEY (account_id) REFERENCES accounts (account_id),
    INDEX idx_audit_account (account_id, entry_id)
) ENGINE = InnoDB;

-- Idempotency: an instruction id is processed at most once.
CREATE TABLE IF NOT EXISTS processed_instructions (
    instruction_id  VARCHAR(64)  NOT NULL PRIMARY KEY,
    status          VARCHAR(16)  NOT NULL,        -- APPLIED / REJECTED
    message         VARCHAR(255),
    processed_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB;
