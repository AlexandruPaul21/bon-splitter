package app.receiptshare

import java.time.LocalDate

// Every monetary value is in the smallest unit of the group's currency.
data class Member(val id: String, val name: String)
data class Group(val id: String, val name: String, val currency: String, val members: List<Member>)
data class Share(val memberId: String, val value: Long)
data class ReceiptItem(
    val description: String,
    val amount: Long,
    val mode: SplitMode = SplitMode.AMOUNT,
    val shares: List<Share> = emptyList(),
    val discount: Long = 0,
    val discountMode: DiscountMode = DiscountMode.PRICE
)
enum class SplitMode { AMOUNT, PERCENT }
enum class DiscountMode { PRICE, FINAL }
enum class ReceiptStatus { DRAFT, CONFIRMED, PAID }
data class Receipt(
    val id: String,
    val groupId: String,
    val status: ReceiptStatus,
    val store: String = "",
    val date: LocalDate? = null,
    val subtotal: Long = 0,
    val discount: Long = 0,
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
    val tax: Long = 0,
    val tip: Long = 0,
    val total: Long = 0,
    val payerId: String? = null,
    val items: List<ReceiptItem> = emptyList()
)
data class MemberBalance(val memberId: String, val name: String, val paid: Long, val owed: Long, val balance: Long)
data class Settlement(val fromId: String, val toId: String, val amount: Long)
data class GroupBalances(val members: List<MemberBalance>, val settlements: List<Settlement>)
