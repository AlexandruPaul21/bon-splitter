package app.receiptshare

import java.math.BigInteger

object SplitCalculator {
    const val MAX_AMOUNT = 100_000_000_000L

    fun charges(receipt: Receipt, members: Set<String>): Map<String, Long> {
        require(receipt.items.isNotEmpty()) { "Add at least one item" }
        require(receipt.payerId in members) { "Choose a payer from this group" }
        listOf(receipt.subtotal, receipt.discount, receipt.wholeDiscount, receipt.tax, receipt.tip, receipt.total).forEach(::validAmount)
        require(receipt.items.sumOf { validAmount(it.amount); validAmount(it.discount); it.amount - it.discount } == receipt.subtotal) { "Items must add up to the subtotal" }
        require(receipt.subtotal > 0 && receipt.total > 0) { "Receipt must have a positive amount" }
        require(receipt.wholeDiscount in 0..receipt.subtotal) { "Whole-receipt discount cannot exceed the subtotal" }
        require(receipt.subtotal - receipt.wholeDiscount + receipt.tax + receipt.tip == receipt.total) { "Subtotal minus discount plus tax and tip must equal total" }

        // Items carry their initial (printed) price and the marked savings on them; the
        // price actually split is the actual price. PRICE subtracts the discount from the
        // item's price, so only its participants pay the actual amount; FINAL charges the
        // full price and pools the saving to be split equally among everyone on the receipt.
        val gross = members.associateWith { 0L }.toMutableMap()
        var pooled = 0L
        for (item in receipt.items) {
            require(item.description.isNotBlank()) { "Each item needs a description" }
            require(item.amount > 0L) { "Each item needs a positive price" }
            require(item.discount in 0..item.amount) { "Item discount cannot exceed the item price" }
            require(item.shares.isNotEmpty()) { "Assign every item" }
            require(item.shares.map { it.memberId }.toSet().size == item.shares.size) { "Duplicate member in item" }
            require(item.shares.all { it.memberId in members && it.value >= 0 && it.value <= MAX_AMOUNT }) { "Invalid item share" }
            val weights = item.shares.associate { it.memberId to it.value }
            // The price actually split: the actual price when the discount is folded into
            // it, the full initial price when the saving is pooled for the end.
            val splitBase = if (item.discountMode == DiscountMode.FINAL) item.amount else item.amount - item.discount
            when (item.mode) {
                SplitMode.AMOUNT -> {
                    require(item.shares.sumOf { it.value } == splitBase) { "Amount shares must add up to the price being split" }
                    item.shares.forEach { share -> gross[share.memberId] = gross.getValue(share.memberId) + share.value }
                }
                SplitMode.PERCENT -> {
                    require(item.shares.sumOf { it.value } == 10_000L) { "Percentage shares must add up to 100%" }
                    prorate(splitBase, weights).forEach { (id, amount) -> gross[id] = gross.getValue(id) + amount }
                }
            }
            if (item.discountMode == DiscountMode.FINAL) pooled += item.discount
        }
        // Savings pooled by FINAL items are split equally at the end among everyone on the
        // receipt, i.e. everyone assigned to at least one item, whatever they pay.
        val participants = receipt.items.flatMap { item -> item.shares.map { it.memberId } }.distinct()
        val poolShare = if (pooled == 0L) emptyMap() else prorate(pooled, participants.associateWith { 1L })
        var net = gross.mapValues { (id, amount) -> amount - (poolShare[id] ?: 0L) }

        // The whole-receipt discount is a deduction from the subtotal that reduces what
        // people pay: MANUAL takes the user's per-person amounts, PRODUCTS prorates it
        // across the products so only their buyers benefit.
        if (receipt.wholeDiscount > 0) {
            when (receipt.discountMode) {
                ReceiptDiscountMode.MANUAL -> {
                    require(receipt.discountShares.map { it.memberId }.toSet().size == receipt.discountShares.size) { "Duplicate member in the discount split" }
                    require(receipt.discountShares.all { it.memberId in members && it.value >= 0 && it.value <= MAX_AMOUNT }) { "Invalid discount share" }
                    require(receipt.discountShares.sumOf { it.value } == receipt.wholeDiscount) { "Manual discount shares must add up to the whole-receipt discount" }
                    net = net.mapValues { (id, amount) -> amount - (receipt.discountShares.firstOrNull { it.memberId == id }?.value ?: 0L) }
                }
                ReceiptDiscountMode.PRODUCTS -> {
                    val shares = prorate(receipt.wholeDiscount, gross)
                    net = net.mapValues { (id, amount) -> amount - shares.getValue(id) }
                }
            }
        }

        // An equal end-of-receipt split can push someone below zero (they effectively get
        // money back); tax and tip are then only prorated over those who still pay.
        val taxable = net.filterValues { it > 0 }
        require(taxable.isNotEmpty() || receipt.tax + receipt.tip == 0L) { "Cannot distribute tax or tip on a fully discounted receipt" }
        val tax = prorate(receipt.tax, taxable)
        val tip = prorate(receipt.tip, taxable)
        return net.mapValues { (id, amount) -> amount + (tax[id] ?: 0L) + (tip[id] ?: 0L) }
            .also { require(it.values.sum() == receipt.total) { "Receipt does not reconcile" } }
    }

    fun balances(members: List<Member>, receipts: List<Receipt>): GroupBalances {
        val ids = members.map { it.id }.toSet()
        val paid = ids.associateWith { 0L }.toMutableMap()
        val owed = ids.associateWith { 0L }.toMutableMap()
        receipts.filter { it.status == ReceiptStatus.CONFIRMED }.forEach { receipt ->
            val charges = charges(receipt, ids)
            paid[receipt.payerId!!] = paid.getValue(receipt.payerId) + receipt.total
            charges.forEach { (id, amount) -> owed[id] = owed.getValue(id) + amount }
        }
        val accounts = members.map { member ->
            MemberBalance(member.id, member.name, paid.getValue(member.id), owed.getValue(member.id), paid.getValue(member.id) - owed.getValue(member.id))
        }
        require(accounts.sumOf { it.balance } == 0L) { "Balances do not reconcile" }
        val creditors = accounts.filter { it.balance > 0 }.map { it.memberId to it.balance }.toMutableList()
        val debtors = accounts.filter { it.balance < 0 }.map { it.memberId to -it.balance }.toMutableList()
        val transfers = mutableListOf<Settlement>()
        var creditIndex = 0
        var debtIndex = 0
        while (creditIndex < creditors.size && debtIndex < debtors.size) {
            val amount = minOf(creditors[creditIndex].second, debtors[debtIndex].second)
            transfers += Settlement(debtors[debtIndex].first, creditors[creditIndex].first, amount)
            creditors[creditIndex] = creditors[creditIndex].copy(second = creditors[creditIndex].second - amount)
            debtors[debtIndex] = debtors[debtIndex].copy(second = debtors[debtIndex].second - amount)
            if (creditors[creditIndex].second == 0L) creditIndex++
            if (debtors[debtIndex].second == 0L) debtIndex++
        }
        return GroupBalances(accounts, transfers)
    }

    private fun validAmount(amount: Long) {
        require(amount in 0..MAX_AMOUNT) { "Amounts must be between 0 and $MAX_AMOUNT minor units" }
    }

    // Largest remainder, with member ID as the deterministic tie-breaker.
    private fun prorate(amount: Long, weights: Map<String, Long>): Map<String, Long> {
        val total = weights.values.sum()
        require(amount == 0L || total > 0) { "Cannot distribute a charge without item shares" }
        if (total == 0L) return weights.mapValues { 0L }
        val denominator = BigInteger.valueOf(total)
        val portions = weights.map { (id, weight) ->
            val parts = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(weight)).divideAndRemainder(denominator)
            Portion(id, parts[0].longValueExact(), parts[1])
        }
        var remaining = amount - portions.sumOf { it.value }
        val rounded = portions.associate { it.id to it.value }.toMutableMap()
        portions.sortedWith(compareByDescending<Portion> { it.remainder }.thenBy { it.id }).forEach { portion ->
            if (remaining > 0) { rounded[portion.id] = rounded.getValue(portion.id) + 1; remaining-- }
        }
        return rounded
    }

    private data class Portion(val id: String, val value: Long, val remainder: BigInteger)
}
