-- V3 is reserved for Slice 3's period table.

-- Seed minimal chart of accounts so existing tests pass before any application traffic.
INSERT INTO accounts (code, name, type, currency, parent_code, active) VALUES
    ('1000', 'Cash',                'ASSET',   'USD', NULL, TRUE),
    ('1100', 'Accounts Receivable', 'ASSET',   'USD', NULL, TRUE),
    ('3000', 'Owner Equity',        'EQUITY',  'USD', NULL, TRUE),
    ('4000', 'Revenue',             'REVENUE', 'USD', NULL, TRUE);

-- Tighten the existing postings.account_code into a real FK.
ALTER TABLE postings
    ADD CONSTRAINT postings_account_code_fk
    FOREIGN KEY (account_code) REFERENCES accounts(code) ON UPDATE CASCADE;
