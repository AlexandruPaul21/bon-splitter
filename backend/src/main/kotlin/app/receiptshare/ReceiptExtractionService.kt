package app.receiptshare

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

@Service
class ReceiptExtractionService(
    @Value("\${receipt-share.data-dir}") private val dataDir: String,
    @Value("\${receipt-share.tesseract}") private val tesseract: String
) {
    private val ocrSlots = Semaphore(2)
    private val itemPattern = Regex("^(.{2,80}?)\\s+(?:[€£$]\\s*)?(\\d{1,8}[.,]\\d{2})\\s*$")
    private val pricePattern = Regex("(\\d{1,8}[.,]\\d{2})\\s*$")
    private val datePattern = Regex("\\b(20\\d{2})[-/.](\\d{1,2})[-/.](\\d{1,2})\\b")
    private val excluded = Regex("\\b(total|subtotal|sub total|tax|vat|tip|discount|change|balance|cash|card|visa|mastercard)\\b", RegexOption.IGNORE_CASE)

    data class Extracted(val edit: ReceiptEdit, val note: String)
    data class Saved(val storedName: String, val fileName: String, val mediaType: String, val extracted: Extracted)

    fun store(file: MultipartFile): Saved {
        if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a receipt file, or create a blank draft instead")
        if (file.size > 8L * 1024 * 1024) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Receipts must be 8 MB or smaller")
        val bytes = file.bytes
        val pdf = bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())
        val jpeg = bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        val png = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        val type = when {
            pdf -> "application/pdf"
            jpeg -> "image/jpeg"
            png -> "image/png"
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF, JPEG, and PNG receipts are supported")
        }
        val original = file.originalFilename?.substringAfterLast('/')?.substringAfterLast('\\')?.take(160)?.ifBlank { "Receipt" } ?: "Receipt"
        val dir = Path.of(dataDir).toAbsolutePath().normalize().resolve("files")
        Files.createDirectories(dir)
        val name = java.util.UUID.randomUUID().toString()
        val target = dir.resolve(name)
        Files.write(target, bytes)
        val extraction = try {
            when (type) {
                "application/pdf" -> extractPdf(bytes)
                else -> extractImage(bytes, type)
            }
        } catch (error: ResponseStatusException) {
            Files.deleteIfExists(target)
            throw error
        } catch (_: Exception) {
            Extracted(ReceiptEdit(), "Could not read this receipt; enter its details manually. The original is saved privately.")
        }
        return Saved(name, original, type, extraction)
    }

    fun path(storedName: String): Path {
        require(storedName.matches(Regex("[a-f0-9-]{36}"))) { "Invalid file reference" }
        return Path.of(dataDir).toAbsolutePath().normalize().resolve("files").resolve(storedName)
    }

    private fun extractPdf(bytes: ByteArray): Extracted = Loader.loadPDF(bytes).use { doc ->
        if (doc.numberOfPages !in 1..6) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF must contain 1–6 pages")
        val text = PDFTextStripper().getText(doc).take(50_000)
        if (text.count { it.isLetterOrDigit() } > 40) parse(text, "PDF text was read locally. Check every line before confirming.")
        else {
            val renderer = PDFRenderer(doc)
            val scanned = (0 until doc.numberOfPages.coerceAtMost(2)).joinToString("\n") { page ->
                val box = doc.getPage(page).mediaBox
                if (box.width <= 0 || box.height <= 0 || box.width * box.height > 1_500_000f)
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF page is too large to process")
                ocr(renderer.renderImageWithDPI(page, 130f))
            }
            parse(scanned, "Scanned PDF was read with local OCR (first two pages). Check and correct the draft before confirming.")
        }
    }

    private fun extractImage(bytes: ByteArray, type: String): Extracted {
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read image")
        stream.use {
            val readers = ImageIO.getImageReaders(it)
            if (!readers.hasNext()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid $type image")
            val reader = readers.next()
            try {
                reader.input = it
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width.toLong() * height > 16_000_000)
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Image is too large to process")
                return parse(ocr(reader.read(0)), "Photo was read with local OCR. Check and correct the draft before confirming.")
            } finally { reader.dispose() }
        }
    }

    private fun ocr(image: BufferedImage): String {
        if (!ocrSlots.tryAcquire()) return ""
        try {
            val tempDir = Path.of(dataDir).toAbsolutePath().normalize().resolve("ocr-temp")
            Files.createDirectories(tempDir)
            val work = Files.createTempFile(tempDir, "receipt-ocr-", ".png")
            try {
                ImageIO.write(image, "png", work.toFile())
                val output = Files.createTempFile(tempDir, "receipt-text-", ".txt")
                try {
                    val process = ProcessBuilder(tesseract, work.toString(), output.toString().removeSuffix(".txt"), "-l", "eng", "--psm", "6")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start()
                    if (!process.waitFor(12, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); return "" }
                    return if (process.exitValue() == 0) Files.readString(output).take(50_000) else ""
                } catch (_: java.io.IOException) {
                    return ""
                } finally { Files.deleteIfExists(output) }
            } finally { Files.deleteIfExists(work) }
        } finally { ocrSlots.release() }
    }

    private fun parse(text: String, note: String): Extracted {
        val lines = text.lines().map(String::trim).filter { it.isNotBlank() }.take(300)
        val store = lines.firstOrNull()?.take(160) ?: ""
        val date = datePattern.find(text)?.let { match ->
            runCatching { java.time.LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()) }.getOrNull()
        }
        val items = lines.mapNotNull { line ->
            if (excluded.containsMatchIn(line)) return@mapNotNull null
            val match = itemPattern.find(line) ?: return@mapNotNull null
            val cents = money(match.groupValues[2]) ?: return@mapNotNull null
            if (cents <= 0 || cents > SplitCalculator.MAX_AMOUNT) return@mapNotNull null
            ReceiptItem(match.groupValues[1].trim(), cents)
        }.take(100)
        val total = lines.lastOrNull { it.contains(Regex("\\btotal\\b", RegexOption.IGNORE_CASE)) && !it.contains(Regex("\\bsubtotal\\b", RegexOption.IGNORE_CASE)) }
            ?.let { pricePattern.find(it)?.groupValues?.get(1)?.let(::money) }
        val subtotal = items.sumOf { it.amount }
        return Extracted(
            ReceiptEdit(store = store, date = date, subtotal = subtotal, total = total ?: subtotal, items = items),
            "$note ${if (items.isEmpty()) "No items recognized; enter them manually." else "Items and totals are guesses; verify all amounts and add tax, tip or discounts as needed."}"
        )
    }

    private fun money(input: String): Long? {
        val normalized = input.replace(',', '.')
        val parts = normalized.split('.')
        return if (parts.size == 2 && parts[1].length == 2)
            parts[0].toLongOrNull()?.takeIf { it in 0..(SplitCalculator.MAX_AMOUNT / 100) }
                ?.let { whole -> parts[1].toLongOrNull()?.let { cents -> whole * 100 + cents } }
        else null
    }
}
