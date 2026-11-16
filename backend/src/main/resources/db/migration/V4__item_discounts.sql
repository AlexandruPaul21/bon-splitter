-- Per-item discounts: each item carries its marked savings and a mode deciding
-- whether the discount stays on the product (PRICE) or is shared with everyone at
-- the final distribution (FINAL).
ALTER TABLE receipt_items ADD COLUMN discount INTEGER NOT NULL DEFAULT 0;
ALTER TABLE receipt_items ADD COLUMN discount_mode TEXT NOT NULL DEFAULT 'PRICE' CHECK (discount_mode IN ('PRICE', 'FINAL'));

-- Prorate each receipt's historical aggregate discount across its items so the
-- editor's auto-summed discount stays consistent with the stored totals. Leftover
-- rounding is assigned to the last item.
WITH alloc AS (
    SELECT ri.id AS item_id,
           CAST(ri.amount * r.discount / r.subtotal AS INTEGER) AS part,
           ROW_NUMBER() OVER (PARTITION BY ri.receipt_id ORDER BY ri.position DESC) AS rn,
           CAST(r.discount - SUM(CAST(ri.amount * r.discount / r.subtotal AS INTEGER)) OVER (PARTITION BY ri.receipt_id) AS INTEGER) AS leftover
    FROM receipt_items ri
    JOIN receipts r ON r.id = ri.receipt_id
    WHERE r.discount > 0 AND r.subtotal > 0
)
UPDATE receipt_items
SET discount = (SELECT part + (CASE WHEN rn = 1 THEN leftover ELSE 0 END) FROM alloc WHERE alloc.item_id = receipt_items.id)
WHERE EXISTS (SELECT 1 FROM alloc WHERE alloc.item_id = receipt_items.id);
