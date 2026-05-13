CREATE TABLE periods (
    year_month    CHAR(7)     PRIMARY KEY,
    status        VARCHAR(8)  NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    closed_at     TIMESTAMPTZ,
    closed_by     VARCHAR(200),
    reopened_at   TIMESTAMPTZ,
    reopened_by   VARCHAR(200),
    CONSTRAINT periods_yearmonth_format CHECK (year_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT periods_closed_has_metadata CHECK (
        status = 'OPEN'
        OR (status = 'CLOSED' AND closed_at IS NOT NULL AND closed_by IS NOT NULL)
    )
);
