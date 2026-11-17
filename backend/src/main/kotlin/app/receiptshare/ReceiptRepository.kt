package app.receiptshare

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.Currency
import java.util.UUID

@Repository
class ReceiptRepository(private val jdbc: JdbcTemplate) {
    fun groups(): List<Group> = jdbc.query("SELECT id, name, currency FROM groups ORDER BY created_at DESC, id DESC", { rs, _ ->
        Group(rs.getString("id"), rs.getString("name"), rs.getString("currency"), members(rs.getString("id")))
    })

    fun group(id: String): Group = jdbc.query("SELECT id, name, currency FROM groups WHERE id = ?", { rs, _ ->
        Group(rs.getString("id"), rs.getString("name"), rs.getString("currency"), members(id))
    }, id).firstOrNull() ?: notFound()

    fun createGroup(name: String, currency: String): Group {
        require(name.isNotBlank() && name.length <= 100) { "Group name must be 1–100 characters" }
        require(currency.matches(Regex("[A-Z]{3}")) && runCatching { Currency.getInstance(currency).defaultFractionDigits == 2 }.getOrDefault(false)) {
            "Choose a valid two-decimal currency (for example EUR, USD, or GBP)"
        }
        val id = UUID.randomUUID().toString()
        jdbc.update("INSERT INTO groups(id, name, currency) VALUES (?, ?, ?)", id, name.trim(), currency)
        return group(id)
    }

    fun members(groupId: String): List<Member> = jdbc.query("SELECT id, name FROM members WHERE group_id = ? ORDER BY rowid", { rs, _ ->
        Member(rs.getString("id"), rs.getString("name"))
    }, groupId)

    fun addMember(groupId: String, name: String): Group {
        group(groupId)
        require(name.isNotBlank() && name.length <= 100) { "Member name must be 1–100 characters" }
        try {
            jdbc.update("INSERT INTO members(id, group_id, name) VALUES (?, ?, ?)", UUID.randomUUID().toString(), groupId, name.trim())
        } catch (_: org.springframework.dao.DataAccessException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "That member already exists")
        }
        return group(groupId)
    }

    fun deleteMember(groupId: String, memberId: String) {
        group(groupId)
        if (jdbc.queryForObject("SELECT COUNT(*) FROM receipts WHERE group_id = ? AND payer_id = ?", Int::class.java, groupId, memberId) > 0 ||
            jdbc.queryForObject("SELECT COUNT(*) FROM allocations a JOIN receipt_items i ON a.item_id = i.id JOIN receipts r ON i.receipt_id = r.id WHERE r.group_id = ? AND a.member_id = ?", Int::class.java, groupId, memberId) > 0
        ) throw ResponseStatusException(HttpStatus.CONFLICT, "This person appears on a receipt; keep them in the group")
        if (jdbc.update("DELETE FROM members WHERE id = ? AND group_id = ?", memberId, groupId) == 0) notFound()
    }

    fun receipts(groupId: String): List<Receipt> {
        group(groupId)
        return jdbc.query("SELECT id FROM receipts WHERE group_id = ? ORDER BY CASE status WHEN 'DRAFT' THEN 0 WHEN 'CONFIRMED' THEN 1 ELSE 2 END, created_at DESC, rowid DESC", { rs, _ -> receipt(rs.getString(1)) }, groupId)
    }

    fun receipt(id: String): Receipt {
        val row = jdbc.query("SELECT * FROM receipts WHERE id = ?", { rs, _ ->
            Receipt(
                id = rs.getString("id"), groupId = rs.getString("group_id"),
                status = ReceiptStatus.valueOf(rs.getString("status")), store = rs.getString("store"),
                date = rs.getString("receipt_date")?.let(LocalDate::parse),
                subtotal = rs.getLong("subtotal"), discount = rs.getLong("discount"),
                wholeDiscount = rs.getLong("whole_discount"),
                discountMode = ReceiptDiscountMode.valueOf(rs.getString("discount_mode")),
                tax = rs.getLong("tax"), tip = rs.getLong("tip"), total = rs.getLong("total"),
                payerId = rs.getString("payer_id"), extractionNote = rs.getString("extraction_note"),
                fileName = rs.getString("file_name")
            )
        }, id).firstOrNull() ?: notFound()
        val items = jdbc.query("SELECT id, description, amount, mode, discount, discount_mode FROM receipt_items WHERE receipt_id = ? ORDER BY position", { rs, _ ->
            ReceiptItem(rs.getString("description"), rs.getLong("amount"), SplitMode.valueOf(rs.getString("mode")),
                jdbc.query("SELECT member_id, value FROM allocations WHERE item_id = ? ORDER BY member_id", { inner, _ -> Share(inner.getString(1), inner.getLong(2)) }, rs.getString("id")),
                discount = rs.getLong("discount"), discountMode = DiscountMode.valueOf(rs.getString("discount_mode")))
        }, id)
        val discountShares = jdbc.query("SELECT member_id, value FROM discount_shares WHERE receipt_id = ? ORDER BY member_id", { rs, _ ->
            Share(rs.getString(1), rs.getLong(2))
        }, id)
        return row.copy(items = items, discountShares = discountShares)
    }

    fun file(id: String): Pair<String, String>? = jdbc.query("SELECT stored_name, media_type FROM receipts WHERE id = ?", { rs, _ ->
        rs.getString(1)?.let { it to rs.getString(2) }
    }, id).firstOrNull()

    @Transactional
    fun createDraft(groupId: String, edit: ReceiptEdit, note: String?, fileName: String?, storedName: String?, mediaType: String?): Receipt {
        group(groupId)
        val id = UUID.randomUUID().toString()
        jdbc.update("""INSERT INTO receipts(id, group_id, status, file_name, stored_name, media_type, extraction_note)
            VALUES (?, ?, 'DRAFT', ?, ?, ?, ?)""", id, groupId, fileName, storedName, mediaType, note)
        return update(id, edit)
    }

    @Transactional
    fun update(id: String, edit: ReceiptEdit): Receipt {
        val old = receipt(id)
        if (old.status == ReceiptStatus.PAID) throw ResponseStatusException(HttpStatus.CONFLICT, "Paid receipts cannot be edited")
        require(edit.store.length <= 160 && edit.items.size <= 200) { "Too many items or a store name that's too long" }
        listOf(edit.subtotal, edit.discount, edit.wholeDiscount, edit.tax, edit.tip, edit.total).forEach { require(it in 0..SplitCalculator.MAX_AMOUNT) { "Invalid amount" } }
        val memberIds = members(old.groupId).map { it.id }.toSet()
        require(edit.payerId == null || edit.payerId in memberIds) { "Payer must belong to the group" }
        require(edit.discountShares.size <= 100) { "Too many discount shares" }
        require(edit.discountShares.all { it.memberId in memberIds && it.value >= 0 && it.value <= SplitCalculator.MAX_AMOUNT }) { "Invalid discount share or member" }
        require(edit.discountShares.map { it.memberId }.distinct().size == edit.discountShares.size) { "Duplicate member in discount split" }
        edit.items.forEach { item ->
            require(item.description.length <= 200 && item.amount in 0..SplitCalculator.MAX_AMOUNT) { "Invalid item" }
            require(item.discount in 0..item.amount) { "Item discount cannot exceed the item price" }
            require(item.shares.size <= 100) { "Too many shares" }
            require(item.shares.all { it.memberId in memberIds && it.value >= 0 && it.value <= SplitCalculator.MAX_AMOUNT }) { "Invalid share or member" }
            require(item.shares.map { it.memberId }.distinct().size == item.shares.size) { "Duplicate member in item" }
        }
        if (old.status == ReceiptStatus.CONFIRMED) {
            SplitCalculator.charges(
                old.copy(subtotal = edit.subtotal, discount = edit.discount, wholeDiscount = edit.wholeDiscount,
                    discountMode = edit.discountMode, discountShares = edit.discountShares,
                    tax = edit.tax, tip = edit.tip, total = edit.total, payerId = edit.payerId, items = edit.items),
                memberIds
            )
        }
        jdbc.update("""UPDATE receipts SET store = ?, receipt_date = ?, subtotal = ?, discount = ?, whole_discount = ?, discount_mode = ?, tax = ?, tip = ?, total = ?, payer_id = ? WHERE id = ?""",
            edit.store.trim(), edit.date?.toString(), edit.subtotal, edit.discount, edit.wholeDiscount, edit.discountMode.name, edit.tax, edit.tip, edit.total, edit.payerId, id)
        jdbc.update("DELETE FROM allocations WHERE item_id IN (SELECT id FROM receipt_items WHERE receipt_id = ?)", id)
        jdbc.update("DELETE FROM receipt_items WHERE receipt_id = ?", id)
        jdbc.update("DELETE FROM discount_shares WHERE receipt_id = ?", id)
        edit.items.forEachIndexed { index, item ->
            val itemId = UUID.randomUUID().toString()
            jdbc.update("INSERT INTO receipt_items(id, receipt_id, position, description, amount, mode, discount, discount_mode) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                itemId, id, index, item.description.trim(), item.amount, item.mode.name, item.discount, item.discountMode.name)
            item.shares.forEach { share -> jdbc.update("INSERT INTO allocations(item_id, member_id, value) VALUES (?, ?, ?)", itemId, share.memberId, share.value) }
        }
        edit.discountShares.forEach { share -> jdbc.update("INSERT INTO discount_shares(receipt_id, member_id, value) VALUES (?, ?, ?)", id, share.memberId, share.value) }
        return receipt(id)
    }

    @Transactional
    fun confirm(id: String): Receipt {
        val receipt = receipt(id)
        if (receipt.status != ReceiptStatus.DRAFT) throw ResponseStatusException(HttpStatus.CONFLICT, "Already confirmed")
        require(receipt.store.isNotBlank() && receipt.date != null) { "Enter a store and receipt date" }
        SplitCalculator.charges(receipt, members(receipt.groupId).map { it.id }.toSet())
        jdbc.update("UPDATE receipts SET status = 'CONFIRMED' WHERE id = ?", id)
        return receipt(id)
    }

    fun markGroupPaid(groupId: String): Int {
        group(groupId)
        return jdbc.update("UPDATE receipts SET status = 'PAID' WHERE group_id = ? AND status = 'CONFIRMED'", groupId)
    }

    @Transactional
    fun delete(id: String): String? {
        val row = jdbc.query("SELECT status, stored_name FROM receipts WHERE id = ?", { rs, _ ->
            rs.getString("status") to rs.getString("stored_name")
        }, id).firstOrNull() ?: notFound()
        if (row.first == ReceiptStatus.PAID.name) throw ResponseStatusException(HttpStatus.CONFLICT, "Paid receipts cannot be deleted")
        jdbc.update("DELETE FROM allocations WHERE item_id IN (SELECT id FROM receipt_items WHERE receipt_id = ?)", id)
        jdbc.update("DELETE FROM receipt_items WHERE receipt_id = ?", id)
        jdbc.update("DELETE FROM discount_shares WHERE receipt_id = ?", id)
        jdbc.update("DELETE FROM receipts WHERE id = ?", id)
        return row.second
    }
}

private fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, "Not found")
