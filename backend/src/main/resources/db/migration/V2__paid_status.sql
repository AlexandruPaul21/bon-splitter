-- Rebuild the receipts table so its status CHECK constraint accepts 'PAID'.
-- SQLite cannot alter a CHECK constraint, so recreate the table and copy data.
-- Must run with PRAGMA foreign_keys = OFF (the migration runner does this).

CREATE TABLE receipts_new (
    id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    status TEXT NOT NULL CHECK (status IN ('DRAFT', 'CONFIRMED', 'PAID')),
    file_name TEXT,
    stored_name TEXT,
    media_type TEXT,
    store TEXT NOT NULL DEFAULT '',
    receipt_date TEXT,
    subtotal INTEGER NOT NULL DEFAULT 0,
    discount INTEGER NOT NULL DEFAULT 0,
    tax INTEGER NOT NULL DEFAULT 0,
    tip INTEGER NOT NULL DEFAULT 0,
    total INTEGER NOT NULL DEFAULT 0,
    payer_id TEXT REFERENCES members(id),
    extraction_note TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO receipts_new (id, group_id, status, file_name, stored_name, media_type, store, receipt_date, subtotal, discount, tax, tip, total, payer_id, extraction_note, created_at)
SELECT id, group_id, status, file_name, stored_name, media_type, store, receipt_date, subtotal, discount, tax, tip, total, payer_id, extraction_note, created_at FROM receipts;

DROP TABLE receipts;

ALTER TABLE receipts_new RENAME TO receipts;

CREATE INDEX idx_receipts_group ON receipts(group_id);
