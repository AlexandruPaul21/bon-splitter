-- Marks receipts whose printed prices already include the discounts (for example
-- Kaufland's "Economisiti ... RON!" lines), so those discounts are not subtracted
-- from the total a second time.
ALTER TABLE receipts ADD COLUMN discount_included INTEGER NOT NULL DEFAULT 0;
