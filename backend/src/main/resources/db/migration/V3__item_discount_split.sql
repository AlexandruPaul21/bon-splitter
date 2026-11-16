-- Each item can opt out of the receipt-level discount split.
ALTER TABLE receipt_items ADD COLUMN split_discount INTEGER NOT NULL DEFAULT 1;
