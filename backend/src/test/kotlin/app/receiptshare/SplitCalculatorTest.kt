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
    fun `price mode keeps an item discount with the people who bought the item`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Wine", 1_000, SplitMode.AMOUNT, discount = 100, shares = listOf(Share("a", 1_000))),
                ReceiptItem("Dinner", 1_000, SplitMode.AMOUNT, shares = listOf(Share("b", 1_000)))
            ),
            discount = 100, total = 2_000
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(1_000L, charges.getValue("a"), "Alice's wine already reflects her discount")
        assertEquals(1_000L, charges.getValue("b"), "Bob's item is untouched")
        assertEquals(0L, charges.getValue("c"))
    }

    @Test
    fun `final mode pools an item discount for everyone proportionally`() {
        val receipt = example(
            items = listOf(
                ReceiptItem("Wine", 1_000, SplitMode.AMOUNT, discount = 100, discountMode = DiscountMode.FINAL, shares = listOf(Share("a", 1_000))),
                ReceiptItem("Dinner", 1_000, SplitMode.AMOUNT, shares = listOf(Share("b", 1_000)))
            ),
            discount = 100, total = 2_000
        )
        val charges = SplitCalculator.charges(receipt, people.map { it.id }.toSet())
        assertEquals(1_048L, charges.getValue("a"), "Alice shares her wine saving with everyone")
        assertEquals(952L, charges.getValue("b"), "Bob benefits from the shared saving")
        assertEquals(0L, charges.getValue("c"))
        assertEquals(2_000L, charges.values.sum())
    }

    @Test
    fun `net model rejects totals where the discount is subtracted again`() {
        val receipt = example(
            items = listOf(ReceiptItem("Dinner", 1_000, SplitMode.AMOUNT, shares = listOf(Share("a", 1_000)))),
            discount = 100, total = 900
        )
        assertThrows(IllegalArgumentException::class.java) { SplitCalculator.charges(receipt, people.map { it.id }.toSet()) }
    }

    private fun example(
        status: ReceiptStatus = ReceiptStatus.CONFIRMED,
        items: List<ReceiptItem> = listOf(ReceiptItem("Dinner", 1_000, SplitMode.AMOUNT,
            listOf(Share("a", 500), Share("b", 500)))),
        discount: Long = 0,
        tax: Long = 0,
        tip: Long = 0,
        total: Long = 1_000
    ): Receipt = Receipt("receipt", "group", status, subtotal = items.sumOf { it.amount },
        discount = discount, tax = tax, tip = tip, total = total, payerId = "a", items = items)
}
