CREATE TABLE organizer (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    password_hash TEXT NOT NULL
);

CREATE TABLE groups (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    currency TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE members (
    id TEXT PRIMARY KEY,
    group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    UNIQUE (group_id, name)
);

CREATE TABLE receipts (
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

CREATE TABLE receipt_items (
    id TEXT PRIMARY KEY,
    receipt_id TEXT NOT NULL REFERENCES receipts(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    description TEXT NOT NULL,
    amount INTEGER NOT NULL,
    mode TEXT NOT NULL CHECK (mode IN ('AMOUNT', 'PERCENT')),
    UNIQUE (receipt_id, position)
);

CREATE TABLE allocations (
    item_id TEXT NOT NULL REFERENCES receipt_items(id) ON DELETE CASCADE,
    member_id TEXT NOT NULL REFERENCES members(id),
    value INTEGER NOT NULL,
    PRIMARY KEY (item_id, member_id)
);

CREATE INDEX idx_members_group ON members(group_id);
CREATE INDEX idx_receipts_group ON receipts(group_id);
CREATE INDEX idx_items_receipt ON receipt_items(receipt_id);
