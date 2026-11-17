package app.receiptshare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SplitCalculatorTest {
    private val alice = Member("a", "Alice")
    private val bob = Member("b", "Bob")
    private val carol = Member("c", "Carol")
    private val people = listOf(alice, bob, carol)

    @Test
    fun `percentage rounding conserves cents`() {
        val receipt = example(items = listOf(ReceiptItem("Snack", 1, SplitMode.PERCENT,
            listOf(Share("a", 3333), Share("b", 3333), Share("c", 3334)))))
        val result = SplitCalculator.charges(receipt.copy(subtotal = 1, total = 1), people.map { it.id }.toSet())
        assertEquals(mapOf("a" to 0L, "b" to 0L, "c" to 1L), result)
    }

    @Test
    fun `fixed item shares and receipt wide adjustments reconcile`() {
        val receipt = example(
            items = listOf(ReceiptItem("Dinner", 1_001, SplitMode.AMOUNT,
                listOf(Share("a", 501), Share("b", 500)))),
            discount = 101, tax = 83, tip = 117, total = 1_201
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(1_201L, charges.values.sum(), "Total is the subtotal plus tax and tip; the informational discount does not reduce it")
        assertEquals(0L, charges.getValue("c"))
    }

    @Test
    fun `unconfirmed receipts never change balances`() {
        val draft = example(status = ReceiptStatus.DRAFT)
        val balances = SplitCalculator.balances(people, listOf(draft))
        assertEquals(listOf(0L, 0L, 0L), balances.members.map { it.balance })
        assertEquals(emptyList<Settlement>(), balances.settlements)
    }

    @Test
    fun `settlements move money to payer`() {
        val receipt = example()
        val balances = SplitCalculator.balances(people, listOf(receipt))
        assertEquals(0L, balances.members.sumOf { it.balance })
        assertEquals(listOf(Settlement("b", "a", 500L)), balances.settlements)
    }

    @Test
    fun `rejects missing shares and total mismatch`() {
        val invalid = example(items = listOf(ReceiptItem("Dinner", 1_000, SplitMode.PERCENT, listOf(Share("a", 9900)))))
        assertThrows(IllegalArgumentException::class.java) { SplitCalculator.charges(invalid, people.map { it.id }.toSet()) }
        assertThrows(IllegalArgumentException::class.java) { SplitCalculator.charges(example(total = 999), people.map { it.id }.toSet()) }
    }

    @Test
    fun `price mode subtracts the discount from the people who bought the item`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Wine", 1_000, SplitMode.AMOUNT, discount = 100, shares = listOf(Share("a", 900))),
                ReceiptItem("Dinner", 1_000, SplitMode.AMOUNT, shares = listOf(Share("b", 1_000)))
            ),
            discount = 100, total = 1_900
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(900L, charges.getValue("a"), "Alice's shares are assigned on the actual price and charged as entered")
        assertEquals(1_000L, charges.getValue("b"), "Bob's item is untouched")
        assertEquals(0L, charges.getValue("c"))
    }

    @Test
    fun `price mode assigns shares on the actual price not the initial price`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Wine", 1_000, SplitMode.AMOUNT, discount = 333, shares = listOf(Share("a", 400), Share("b", 267)))
            ),
            discount = 333, total = 667
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(400L, charges.getValue("a"), "Alice pays exactly the actual-price share she was assigned")
        assertEquals(267L, charges.getValue("b"))
        assertEquals(667L, charges.values.sum())
    }

    @Test
    fun `price mode rejects shares that add up to the initial price instead of the actual price`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Wine", 1_000, SplitMode.AMOUNT, discount = 100, shares = listOf(Share("a", 1_000)))
            ),
            discount = 100, total = 900
        )
        assertThrows(IllegalArgumentException::class.java) { SplitCalculator.charges(receipt, people.map { it.id }.toSet()) }
    }

    @Test
    fun `final mode charges the full price and splits the discount equally among people on the receipt`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Wine", 1_000, SplitMode.AMOUNT, discount = 100, discountMode = DiscountMode.FINAL, shares = listOf(Share("a", 1_000))),
                ReceiptItem("Dinner", 3_000, SplitMode.AMOUNT, shares = listOf(Share("b", 3_000)))
            ),
            discount = 100, total = 3_900
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(950L, charges.getValue("a"), "Alice pays the full wine price minus an equal half of the saving")
        assertEquals(2_950L, charges.getValue("b"), "Bob pays the full dinner price minus an equal half of the saving")
        assertEquals(0L, charges.getValue("c"), "Carol is not on the receipt and shares nothing")
        assertEquals(3_900L, charges.values.sum())
    }

    @Test
    fun `final mode splits a pooled discount equally whatever each person pays`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Dinner", 1_000, SplitMode.AMOUNT, discount = 100, discountMode = DiscountMode.FINAL,
                    shares = listOf(Share("a", 334), Share("b", 333), Share("c", 333)))
            ),
            discount = 100, total = 900
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(300L, charges.getValue("a"))
        assertEquals(300L, charges.getValue("b"))
        assertEquals(300L, charges.getValue("c"), "The odd cent goes by deterministic remainder, yet all three end up even")
        assertEquals(900L, charges.values.sum())
    }

    @Test
    fun `final mode allows an equal share to push someone below zero`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Wine", 1_000, SplitMode.AMOUNT, discount = 900, discountMode = DiscountMode.FINAL, shares = listOf(Share("a", 1_000))),
                ReceiptItem("Snack", 100, SplitMode.AMOUNT, shares = listOf(Share("b", 100)))
            ),
            discount = 900, tax = 100, total = 300
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(-350L, charges.getValue("b"), "Bob's equal share of the saving is larger than his snack; he gets money back")
        assertEquals(650L, charges.getValue("a"), "Alice pays her full wine price and the tax; Bob owes nothing toward it")
        assertEquals(0L, charges.getValue("c"))
        assertEquals(300L, charges.values.sum())
    }

    @Test
    fun `percent mode subtracts the discount when shared in the product price`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Wine", 1_000, SplitMode.PERCENT, discount = 100, shares = listOf(Share("a", 5_000), Share("b", 5_000)))
            ),
            discount = 100, total = 900
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(450L, charges.getValue("a"))
        assertEquals(450L, charges.getValue("b"))
        assertEquals(900L, charges.values.sum())
    }

    @Test
    fun `percent mode final split is equal and not proportional`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Wine", 1_000, SplitMode.PERCENT, discount = 200, discountMode = DiscountMode.FINAL,
                    shares = listOf(Share("a", 6_000), Share("b", 4_000)))
            ),
            discount = 200, total = 800
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(500L, charges.getValue("a"), "Alice pays 600 minus an equal half of the saving, not 60% of it")
        assertEquals(300L, charges.getValue("b"), "Bob pays 400 minus an equal half of the saving")
        assertEquals(800L, charges.values.sum())
    }

    @Test
    fun `rejects a discount larger than the item price`() {
        val receipt = example(
            items = listOf(ReceiptItem("Wine", 100, SplitMode.AMOUNT, discount = 200, shares = listOf(Share("a", 100)))),
            total = 1_900
        )
        assertThrows(IllegalArgumentException::class.java) { SplitCalculator.charges(receipt, people.map { it.id }.toSet()) }
    }

    @Test
    fun `net model rejects totals where the discount is subtracted again`() {
        val receipt = example(
            items = listOf(ReceiptItem("Dinner", 1_000, SplitMode.AMOUNT, shares = listOf(Share("a", 1_000)))),
            discount = 100, total = 900
        )
        assertThrows(IllegalArgumentException::class.java) { SplitCalculator.charges(receipt, people.map { it.id }.toSet()) }
    }

    @Test
    fun `products mode prorates the whole-receipt discount across the items`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Wine", 1_000, SplitMode.AMOUNT, shares = listOf(Share("a", 1_000))),
                ReceiptItem("Dinner", 1_000, SplitMode.AMOUNT, shares = listOf(Share("b", 1_000)))
            ),
            wholeDiscount = 400, discountMode = ReceiptDiscountMode.PRODUCTS, total = 1_600
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(800L, charges.getValue("a"))
        assertEquals(800L, charges.getValue("b"))
        assertEquals(0L, charges.getValue("c"))
        assertEquals(1_600L, charges.values.sum())
    }

    @Test
    fun `manual mode applies the user assigned discount amounts`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Wine", 1_000, SplitMode.AMOUNT, shares = listOf(Share("a", 1_000))),
                ReceiptItem("Dinner", 1_000, SplitMode.AMOUNT, shares = listOf(Share("b", 1_000)))
            ),
            wholeDiscount = 400, discountMode = ReceiptDiscountMode.MANUAL,
            discountShares = listOf(Share("a", 300), Share("b", 100)), total = 1_600
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(700L, charges.getValue("a"), "Alice keeps the bigger share of the saving")
        assertEquals(900L, charges.getValue("b"))
        assertEquals(0L, charges.getValue("c"))
        assertEquals(1_600L, charges.values.sum())
    }

    @Test
    fun `manual mode shares the tax over the discounted amounts`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Wine", 1_000, SplitMode.AMOUNT, shares = listOf(Share("a", 1_000))),
                ReceiptItem("Dinner", 1_000, SplitMode.AMOUNT, shares = listOf(Share("b", 1_000)))
            ),
            wholeDiscount = 400, discountMode = ReceiptDiscountMode.MANUAL,
            discountShares = listOf(Share("a", 300), Share("b", 100)), tax = 160, total = 1_760
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(770L, charges.getValue("a"))
        assertEquals(990L, charges.getValue("b"))
        assertEquals(1_760L, charges.values.sum())
    }

    @Test
    fun `manual mode rejects shares that do not add up to the discount`() {
        val receipt = example(
            wholeDiscount = 400, discountMode = ReceiptDiscountMode.MANUAL,
            discountShares = listOf(Share("a", 300), Share("b", 50)), total = 1_600
        )
        assertThrows(IllegalArgumentException::class.java) { SplitCalculator.charges(receipt, people.map { it.id }.toSet()) }
    }

    @Test
    fun `whole-receipt discount larger than the subtotal is rejected`() {
        val receipt = example(wholeDiscount = 2_500, total = 1_600)
        assertThrows(IllegalArgumentException::class.java) { SplitCalculator.charges(receipt, people.map { it.id }.toSet()) }
    }

    @Test
    fun `manual discount can push someone below zero and tax stays on those who pay`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Wine", 300, SplitMode.AMOUNT, shares = listOf(Share("a", 300))),
                ReceiptItem("Dinner", 1_700, SplitMode.AMOUNT, shares = listOf(Share("b", 1_700)))
            ),
            wholeDiscount = 400, discountMode = ReceiptDiscountMode.MANUAL,
            discountShares = listOf(Share("a", 400), Share("b", 0)), tax = 100, total = 1_700
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(-100L, charges.getValue("a"), "Alice's discount is larger than her wine; tax is not charged on her")
        assertEquals(1_800L, charges.getValue("b"), "Bob covers the whole tax on top of his dinner")
        assertEquals(1_700L, charges.values.sum())
    }

    private fun example(
        status: ReceiptStatus = ReceiptStatus.CONFIRMED,
        items: List<ReceiptItem> = listOf(ReceiptItem("Dinner", 1_000, SplitMode.AMOUNT,
            listOf(Share("a", 500), Share("b", 500)))),
        discount: Long = 0,
        wholeDiscount: Long = 0,
        discountMode: ReceiptDiscountMode = ReceiptDiscountMode.MANUAL,
        discountShares: List<Share> = emptyList(),
        tax: Long = 0,
        tip: Long = 0,
        total: Long = 1_000
    ): Receipt = Receipt("receipt", "group", status, subtotal = items.sumOf { it.amount - it.discount },
        discount = discount, wholeDiscount = wholeDiscount, discountMode = discountMode,
        discountShares = discountShares, tax = tax, tip = tip, total = total, payerId = "a", items = items)
}
