CREATE TABLE accounts (
    code          VARCHAR(64) PRIMARY KEY,
    name          VARCHAR(200) NOT NULL,
    type          VARCHAR(16)  NOT NULL CHECK (type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    currency      CHAR(3)      NOT NULL,
    parent_code   VARCHAR(64)  REFERENCES accounts(code) ON UPDATE CASCADE,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT account_not_self_parent CHECK (parent_code IS NULL OR parent_code <> code)
);

CREATE INDEX idx_accounts_parent_code ON accounts(parent_code);
CREATE INDEX idx_accounts_active_true ON accounts(active) WHERE active = TRUE;
