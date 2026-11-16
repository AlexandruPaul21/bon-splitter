package app.receiptshare

import java.math.BigInteger

object SplitCalculator {
    const val MAX_AMOUNT = 100_000_000_000L

    fun charges(receipt: Receipt, members: Set<String>): Map<String, Long> {
        require(receipt.items.isNotEmpty()) { "Add at least one item" }
        require(receipt.payerId in members) { "Choose a payer from this group" }
        listOf(receipt.subtotal, receipt.discount, receipt.tax, receipt.tip, receipt.total).forEach(::validAmount)
        require(receipt.items.sumOf { validAmount(it.amount); it.amount } == receipt.subtotal) { "Items must add up to the subtotal" }
        require(receipt.subtotal > 0 && receipt.total > 0) { "Receipt must have a positive amount" }
        require(receipt.subtotal + receipt.tax + receipt.tip == receipt.total) { "Subtotal + tax + tip must equal total" }

        // Items carry their effective (net) price, so the discount is informational savings
        // that only matters for redistribution: PRICE keeps it in the item's price, FINAL
        // pools it for everyone in proportion to what they pay.
        val gross = members.associateWith { 0L }.toMutableMap()
        var pooled = 0L
        for (item in receipt.items) {
            require(item.description.isNotBlank()) { "Each item needs a description" }
            require(item.amount > 0L) { "Each item needs a positive price" }
            require(item.discount >= 0L) { "Item discount cannot be negative" }
            require(item.shares.isNotEmpty()) { "Assign every item" }
            require(item.shares.map { it.memberId }.toSet().size == item.shares.size) { "Duplicate member in item" }
            require(item.shares.all { it.memberId in members && it.value >= 0 && it.value <= MAX_AMOUNT }) { "Invalid item share" }
            val amounts = when (item.mode) {
                SplitMode.AMOUNT -> {
                    require(item.shares.sumOf { it.value } == item.amount) { "Amount shares must add up to item price" }
                    item.shares.associate { it.memberId to it.value }
                }
                SplitMode.PERCENT -> {
                    require(item.shares.sumOf { it.value } == 10_000L) { "Percentage shares must add up to 100%" }
                    prorate(item.amount, item.shares.associate { it.memberId to it.value })
                }
            }
            amounts.forEach { (id, amount) -> gross[id] = gross.getValue(id) + amount }
            if (item.discountMode == DiscountMode.FINAL) {
                // Share the saving with everyone: gross the item back up and pool the saving.
                pooled += item.discount
                prorate(item.discount, amounts).forEach { (id, amount) -> gross[id] = gross.getValue(id) + amount }
            }
        }
        val poolShare = if (pooled == 0L) gross.mapValues { 0L } else prorate(pooled, gross)
        val net = gross.mapValues { (id, amount) -> amount - poolShare.getValue(id) }
        require(net.values.all { it >= 0 }) { "Discount allocation exceeds someone's item share" }
        require(net.values.sum() > 0 || receipt.tax + receipt.tip == 0L) { "Cannot distribute tax or tip on a fully discounted receipt" }
        val tax = prorate(receipt.tax, net)
        val tip = prorate(receipt.tip, net)
        return net.mapValues { (id, amount) -> amount + tax.getValue(id) + tip.getValue(id) }
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
