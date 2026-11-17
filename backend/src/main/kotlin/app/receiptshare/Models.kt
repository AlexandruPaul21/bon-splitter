package app.receiptshare

import java.time.LocalDate

// Every monetary value is in the smallest unit of the group's currency.
data class Member(val id: String, val name: String)
data class Group(val id: String, val name: String, val currency: String, val members: List<Member>)
data class Share(val memberId: String, val value: Long)
data class ReceiptItem(
    val description: String,
    /** Initial (printed) price of the item, always visible; the actual price that is
     * split is [amount] minus the savings tracked in [discount]. */
    val amount: Long,
    val mode: SplitMode = SplitMode.AMOUNT,
    val shares: List<Share> = emptyList(),
    /** Marked savings on this item, never exceeding [amount]. */
    val discount: Long = 0,
    /** PRICE folds the saving into the item's actual price, so only its participants
     * benefit; FINAL charges the full initial price and splits the saving equally among
     * everyone on the receipt at the end. */
    val discountMode: DiscountMode = DiscountMode.PRICE
)
enum class SplitMode { AMOUNT, PERCENT }
enum class DiscountMode { PRICE, FINAL }
// How a discount that applies to the whole receipt (not one product) is split among
// people: MANUAL lets the user assign each person's saving at the end, PRODUCTS
// prorates it across the products (so only their buyers benefit).
enum class ReceiptDiscountMode { MANUAL, PRODUCTS }
enum class ReceiptStatus { DRAFT, CONFIRMED, PAID }
data class Receipt(
    val id: String,
    val groupId: String,
    val status: ReceiptStatus,
    val store: String = "",
    val date: LocalDate? = null,
    val subtotal: Long = 0,
    val discount: Long = 0,
    val wholeDiscount: Long = 0,
    val discountMode: ReceiptDiscountMode = ReceiptDiscountMode.MANUAL,
    val discountShares: List<Share> = emptyList(),
    val tax: Long = 0,
    val tip: Long = 0,
    val total: Long = 0,
    val payerId: String? = null,
    val items: List<ReceiptItem> = emptyList(),
    val extractionNote: String? = null,
    val fileName: String? = null
)
data class ReceiptEdit(
    val store: String = "",
    val date: LocalDate? = null,
    val subtotal: Long = 0,
    val discount: Long = 0,
    val wholeDiscount: Long = 0,
    val discountMode: ReceiptDiscountMode = ReceiptDiscountMode.MANUAL,
    val discountShares: List<Share> = emptyList(),
    val tax: Long = 0,
    val tip: Long = 0,
    val total: Long = 0,
    val payerId: String? = null,
    val items: List<ReceiptItem> = emptyList()
)
data class MemberBalance(val memberId: String, val name: String, val paid: Long, val owed: Long, val balance: Long)
data class Settlement(val fromId: String, val toId: String, val amount: Long)
data class GroupBalances(val members: List<MemberBalance>, val settlements: List<Settlement>)
