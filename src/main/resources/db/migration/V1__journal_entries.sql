CREATE TABLE journal_entries (
    id            UUID         PRIMARY KEY,
    occurred_on   DATE         NOT NULL,
    description   VARCHAR(500) NOT NULL,
    currency      CHAR(3)      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id                 UUID         PRIMARY KEY,
    journal_entry_id   UUID         NOT NULL REFERENCES journal_entries(id) ON DELETE CASCADE,
    account_code       VARCHAR(64)  NOT NULL,
    side               VARCHAR(6)   NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_minor_units BIGINT       NOT NULL CHECK (amount_minor_units >= 0),
    sequence_in_entry  INT          NOT NULL,
    UNIQUE (journal_entry_id, sequence_in_entry)
);

CREATE INDEX idx_postings_account_code ON postings (account_code);
CREATE INDEX idx_journal_entries_occurred_on ON journal_entries (occurred_on);
