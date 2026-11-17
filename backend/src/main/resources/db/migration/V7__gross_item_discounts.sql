-- Item amounts now hold the gross (printed) price so the editor can really subtract
-- a discount from the product price (effective price = amount − discount). Legacy rows
-- stored the already-netted price, so first rescale each discounted item's AMOUNT
-- allocations to the gross price — largest remainder keeps every cent conserved and
-- member IDs break the ties deterministically — then fold the marked savings back into
-- the amount. PERCENT items keep their basis points (now a share of the gross price).
WITH scaled AS (
    SELECT a.item_id AS item_id,
           a.member_id AS member_id,
           (a.value * (ri.amount + ri.discount)) / ri.amount AS base,
           ROW_NUMBER() OVER (
               PARTITION BY a.item_id
               ORDER BY (a.value * (ri.amount + ri.discount)) % ri.amount DESC, a.member_id
           ) AS rn,
           (ri.amount + ri.discount)
             - SUM((a.value * (ri.amount + ri.discount)) / ri.amount) OVER (PARTITION BY a.item_id) AS leftover
    FROM receipt_items ri
    JOIN allocations a ON a.item_id = ri.id
    WHERE ri.discount > 0 AND ri.mode = 'AMOUNT' AND ri.amount > 0
)
UPDATE allocations
SET value = (
    SELECT s.base + CASE WHEN s.rn <= s.leftover THEN 1 ELSE 0 END
    FROM scaled s
    WHERE s.item_id = allocations.item_id AND s.member_id = allocations.member_id
)
WHERE EXISTS (
    SELECT 1 FROM scaled s2
    WHERE s2.item_id = allocations.item_id AND s2.member_id = allocations.member_id
);

UPDATE receipt_items SET amount = amount + discount WHERE discount > 0;
