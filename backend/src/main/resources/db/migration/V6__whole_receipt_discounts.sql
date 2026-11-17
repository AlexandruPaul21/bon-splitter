-- Whole-receipt discounts (for example a Kaufland Card XTRA promo applied after the
-- Subtotal line) are no longer folded into the item prices. Each receipt keeps the
-- amount separately, a mode deciding how it is split among people, and per-person
-- amounts for the MANUAL mode.
--
-- Existing receipts keep their prorated item discounts and a whole_discount of zero,
-- so they still reconcile exactly as before; the new fields only start applying to
-- receipts parsed or edited after this migration.
ALTER TABLE receipts ADD COLUMN whole_discount INTEGER NOT NULL DEFAULT 0;
ALTER TABLE receipts ADD COLUMN discount_mode TEXT NOT NULL DEFAULT 'MANUAL' CHECK (discount_mode IN ('MANUAL', 'PRODUCTS'));

CREATE TABLE discount_shares (
    receipt_id TEXT NOT NULL REFERENCES receipts(id) ON DELETE CASCADE,
    member_id TEXT NOT NULL REFERENCES members(id),
    value INTEGER NOT NULL,
    PRIMARY KEY (receipt_id, member_id)
);
CREATE INDEX idx_discount_shares_receipt ON discount_shares(receipt_id);
