-- LedgerLite schema for embedded Apache Derby (demo mode + tests).
-- Same logical model as schema-mysql.sql; only dialect differences.

CREATE TABLE accounts (
    account_id   VARCHAR(32)  NOT NULL PRIMARY KEY,
    owner_name   VARCHAR(100) NOT NULL,
    balance      BIGINT       NOT NULL,
    created_at   TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_balance_non_negative CHECK (balance >= 0)
);

CREATE TABLE audit_log (
    entry_id        BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id      VARCHAR(32)  NOT NULL,
    instruction_id  VARCHAR(64)  NOT NULL,
    op_type         VARCHAR(20)  NOT NULL,
    amount          BIGINT       NOT NULL,
    balance_before  BIGINT       NOT NULL,
    balance_after   BIGINT       NOT NULL,
    created_at      TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_account FOREIGN KEY (account_id) REFERENCES accounts (account_id)
);

CREATE INDEX idx_audit_account ON audit_log (account_id, entry_id);

CREATE TABLE processed_instructions (
    instruction_id  VARCHAR(64)  NOT NULL PRIMARY KEY,
    status          VARCHAR(16)  NOT NULL,
    message         VARCHAR(255),
    processed_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP
);
