package app.receiptshare

import java.time.LocalDate

// Turns raw extracted text (PDFBox text or Tesseract OCR) into a reviewable draft.
// Handles Romanian-style receipts where prices carry a trailing VAT-code letter
// (for example "13,99 B"), dates appear as "13.08.26" or "18/08/2026", and inline
// promos/discounts ("Promoție", "Economisire in urma reducerilor") must not become items.
object ReceiptParser {
    data class Result(val edit: ReceiptEdit, val note: String)

    private val price = Regex("\\d{1,8}[.,]\\s?\\d{2}")
    private val quantityContinuation = Regex("^\\d[\\d.,]*\\s*\\*\\s*\\d[\\d.,]*$")
    private val weightContinuation = Regex("^\\d[\\d.,]*\\s*(?:KG|G|BUC|ML|L)\\s*$", RegexOption.IGNORE_CASE)
    private val unitPriceLineRegex = Regex("^\\s*\\d[\\d.,@]*\\s*(?:BUC|KG)\\s*x\\s*\\d", RegexOption.IGNORE_CASE)
    // Kaufland's "Economisiti 42% sau 6,30 RON!" line states how much was saved, and the
    // printed price already reflects that saving, so the item is grossed back up before
    // its effective price is derived. Other markets (Lidl, older Kaufland) print the gross
    // price and subtract the discount separately.
    private val grossUpWord = Regex("economisiti", RegexOption.IGNORE_CASE)
    private val discountWord = Regex("economisire|economisiti|reducere|promo|discount|xtra|returnare", RegexOption.IGNORE_CASE)
    private val excluded = Regex("\\b(total|subtotal|sub total|tax|vat|tva|tip|discount|change|balance|cash|card|visa|mastercard|plata|plată|rest|brut|tranzac)\\b", RegexOption.IGNORE_CASE)
    private val totalLine = Regex("\\btotal\\b", RegexOption.IGNORE_CASE)
    private val subtotalWord = Regex("\\bsubtotal\\b|\\bsub total\\b", RegexOption.IGNORE_CASE)
    private val tvaWord = Regex("\\b(tva|vat)\\b", RegexOption.IGNORE_CASE)
    private val priceSuffix = Regex("[-]?(?:\\s*(?:RON|LEI)[!.]*|[A-Za-z0-9&@.])?")
    private val europeanDate = Regex("\\b(\\d{1,2})[./](\\d{1,2})[./](\\d{2}|\\d{4})\\b")
    private val isoDate = Regex("\\b(20\\d{2})[-/.](\\d{1,2})[-/.](\\d{1,2})\\b")

    fun parse(text: String, note: String): Result {
        val lines = text.lines().map(String::trim).filter { it.isNotBlank() }.take(300)
        val totalIndex = lines.indexOfFirst(::isTotalLine)
        val body = if (totalIndex >= 0) lines.take(totalIndex) else lines
        val printedTotal = lines.filter(::isTotalLine).mapNotNull(::trailingPrice).maxOrNull()

        val store = when {
            text.contains("kaufland", true) -> "Kaufland"
            text.contains("lidl", true) -> "Lidl"
            else -> lines.firstOrNull()?.take(160) ?: ""
        }
        val date = european(text) ?: iso(text)

        val items = mutableListOf<ReceiptItem>()
        var orphan = 0L
        var pending: String? = null
        for (line in body) {
            if (unitPriceLineRegex.containsMatchIn(line)) continue
            val parsed = amountLine(line)
            if (parsed == null) { pending = line.take(160); continue }
            val (desc, cents, isDiscount) = parsed
            if (cents <= 0 || cents > SplitCalculator.MAX_AMOUNT) { pending = null; continue }
            if (isDiscount) {
                // Attach the marked savings to the item it follows so each item can choose
                // how its discount is shared. Kaufland's "Economisiti ... RON!" line already
                // prints the discounted price, so gross the item back up to its list price;
                // other discounts (Lidl, older Kaufland) leave the printed price untouched.
                // Discounts too large for the item they follow are shared by everyone instead.
                if (items.isNotEmpty()) {
                    val index = items.lastIndex
                    val item = items[index]
                    val gross = if (grossUpWord.containsMatchIn(line)) item.amount + cents else item.amount
                    if (gross > cents) items[index] = item.copy(amount = gross, discount = item.discount + cents)
                    else orphan += cents
                } else {
                    orphan += cents
                }
                pending = null
                continue
            }
            if (excluded.containsMatchIn(line)) { pending = null; continue }
            val description = if ((quantityContinuation.matches(desc) || weightContinuation.matches(desc)) && pending != null)
                "$pending (${desc.replace("*", "x")})".take(160) else desc
            items += ReceiptItem(description, cents)
            pending = null
        }

        // Receipt-level discounts (not tied to one item) apply to everyone in proportion
        // to their item's price, so the summary discount always matches the items.
        if (orphan > 0 && items.isNotEmpty()) {
            val totalGross = items.sumOf { it.amount }
            if (totalGross > 0) {
                var remaining = orphan
                items.forEachIndexed { index, item ->
                    val share = if (index == items.lastIndex) remaining else item.amount * orphan / totalGross
                    items[index] = item.copy(discount = item.discount + share)
                    remaining -= share
                }
            }
        }
        // Items always show the effective price: what you actually pay for each one.
        items.forEachIndexed { index, item -> items[index] = item.copy(amount = item.amount - item.discount) }

        val subtotal = items.sumOf { it.amount }
        val discount = items.sumOf { it.discount }
        val total = printedTotal?.takeIf { it == subtotal } ?: subtotal
        val resultNote = "$note ${if (items.isEmpty()) "No items recognized; enter them manually." else "Items and totals are guesses; verify all amounts and add tax, tip or discounts as needed."}"
        return Result(
            ReceiptEdit(store = store, date = date, subtotal = subtotal, discount = discount, total = total, items = items),
            resultNote
        )
    }

    private fun isTotalLine(line: String): Boolean =
        totalLine.containsMatchIn(line) && !subtotalWord.containsMatchIn(line) && !tvaWord.containsMatchIn(line)

    private fun trailingPrice(line: String): Long? = price.find(line)?.value?.let(::money)

    private fun amountLine(line: String): Triple<String, Long, Boolean>? {
        // Ignore quantities such as "0,66L" or "1,5L" where a unit letter is glued to the number.
        val match = price.findAll(line).lastOrNull { line.getOrNull(it.range.last + 1)?.isLetter() != true } ?: return null
        val suffixText = line.substring(match.range.last + 1).trim()
        if (!priceSuffix.matches(suffixText)) return null
        var desc = line.substring(0, match.range.first).trim()
        if (desc.length < 2) return null
        val negative = desc.endsWith("-") || discountWord.containsMatchIn(line)
        desc = desc.removeSuffix("-").trim()
        val cents = money(match.value) ?: return null
        return Triple(desc, cents, negative)
    }

    private fun money(input: String): Long? {
        val normalized = input.replace(" ", "").replace(',', '.')
        val parts = normalized.split('.')
        return if (parts.size == 2 && parts[1].length == 2 && parts[0].all { it.isDigit() } && parts[1].all { it.isDigit() })
            parts[0].toLongOrNull()?.takeIf { it <= SplitCalculator.MAX_AMOUNT / 100 }
                ?.let { whole -> parts[1].toLongOrNull()?.let { cents -> whole * 100 + cents } }
        else null
    }

    private fun european(text: String): LocalDate? {
        for (match in europeanDate.findAll(text)) {
            val day = match.groupValues[1].toIntOrNull() ?: continue
            val month = match.groupValues[2].toIntOrNull() ?: continue
            val rawYear = match.groupValues[3].toIntOrNull() ?: continue
            if (day !in 1..31 || month !in 1..12) continue
            val year = if (rawYear < 100) 2000 + rawYear else rawYear
            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun iso(text: String): LocalDate? {
        val match = isoDate.find(text) ?: return null
        return runCatching {
            LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        }.getOrNull()
    }
}
