package app.receiptshare

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.nio.file.Files
import java.nio.file.Path

// Exercises the real extraction pipeline (PDFBox text + Tesseract OCR) against the
// sample receipts in the repository's `samples/` directory. Skipped automatically when
// the samples or a tesseract binary are not available.
class SampleReceiptExtractionTest {
    private val samples = Path.of("..", "samples").toAbsolutePath().normalize()
    private val service = ReceiptExtractionService(Files.createTempDirectory("receipt-sample-").toString(), "tesseract")

    @Test
    fun `kaufland text pdfs extract items promos and totals`() {
        assumeTrue(Files.isDirectory(samples.resolve("kaufland")))
        assertPdf("kaufland/20260820_145641.pdf", date = "2026-08-13", subtotal = 24_352, discount = 3_082, total = 24_352, items = 30)
        assertPdf("kaufland/20260820_145734.pdf", date = "2026-06-12", subtotal = 42_922, discount = 0, total = 42_922)
        assertPdf("kaufland/20260820_145754.pdf", date = "2026-06-18", subtotal = 4_054, discount = 3_421, total = 4_054, items = 4)
        assertPdf("kaufland/20260820_145814.pdf", date = "2026-05-08", subtotal = 29_912, discount = 1_950, wholeDiscount = 2_500, total = 27_412)
        assertPdf("kaufland/20261113_232857.pdf", date = "2026-10-26", subtotal = 12_906, discount = 630, total = 12_906, items = 12)
        assertPdf("kaufland/20261113_232945.pdf", date = "2026-10-11", subtotal = 9_594, discount = 0, total = 9_594, items = 3)
        assertPdf("kaufland/20261116_223118.pdf", date = "2026-11-16", subtotal = 33_975, discount = 3_878, wholeDiscount = 2_000, total = 31_975, items = 55)
    }

    @Test
    fun `lidl photo receipts extract items and redeption discounts`() {
        assumeTrue(Files.isDirectory(samples.resolve("lidl")))
        assumeTrue(tesseractAvailable())
        assertImage("lidl/recipt-1.jpeg", date = "2026-08-18", subtotal = 1_699, discount = 300, total = 1_699)
        assertImage("lidl/recipt-2.jpeg", date = "2026-08-16", subtotal = 3_201, discount = 1_500, total = 3_201)
        assertImage("lidl/recipt-3.jpeg", date = "2026-07-26", subtotal = 6_728, discount = 2_500, total = 6_728)
        assertImage("lidl/recipt-4.jpeg", date = "2026-10-14", subtotal = 14_742, discount = 2_739, total = 14_742)
    }

    private fun assertPdf(file: String, date: String, subtotal: Long, discount: Long, total: Long, wholeDiscount: Long = 0, items: Int = -1) {
        val bytes = Files.readAllBytes(samples.resolve(file))
        val saved = service.store(MockMultipartFile("file", file, "application/pdf", bytes))
        val edit = saved.extracted.edit
        assertEquals("Kaufland", edit.store, "store for $file")
        assertEquals(date, edit.date.toString(), "date for $file")
        assertEquals(subtotal, edit.subtotal, "subtotal for $file")
        assertEquals(discount, edit.discount, "discount for $file")
        assertEquals(wholeDiscount, edit.wholeDiscount, "whole-receipt discount for $file")
        assertEquals(total, edit.total, "total for $file")
        assertEquals(subtotal - wholeDiscount, edit.total, "net-priced reconciliation for $file")
        if (items >= 0) assertEquals(items, edit.items.size, "item count for $file")
    }

    private fun assertImage(file: String, date: String, subtotal: Long, discount: Long, total: Long) {
        val bytes = Files.readAllBytes(samples.resolve(file))
        val saved = service.store(MockMultipartFile("file", file, "image/jpeg", bytes))
        val edit = saved.extracted.edit
        assertEquals("Lidl", edit.store, "store for $file")
        assertEquals(date, edit.date.toString(), "date for $file")
        assertEquals(subtotal, edit.subtotal, "subtotal for $file")
        assertEquals(discount, edit.discount, "discount for $file")
        assertEquals(0, edit.wholeDiscount, "whole-receipt discount for $file")
        assertEquals(total, edit.total, "total for $file")
        assertEquals(subtotal - edit.wholeDiscount, edit.total, "net-priced reconciliation for $file")
    }

    private fun tesseractAvailable(): Boolean = try {
        ProcessBuilder("tesseract", "--version").redirectErrorStream(true).start().let { it.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && it.exitValue() == 0 }
    } catch (_: Exception) {
        false
    }
}
