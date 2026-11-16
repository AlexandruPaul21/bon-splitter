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
            var missing = false
            val scanned = (0 until doc.numberOfPages.coerceAtMost(2)).joinToString("\n") { page ->
                val box = doc.getPage(page).mediaBox
                if (box.width <= 0 || box.height <= 0 || box.width * box.height > 1_500_000f)
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF page is too large to process")
                val outcome = ocr(renderer.renderImageWithDPI(page, 130f))
                if (outcome.tesseractMissing) missing = true
                outcome.text
            }
            parseOcrText(scanned, missing, "Scanned PDF was read with local OCR (first two pages). Check and correct the draft before confirming.")
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
                val outcome = ocr(reader.read(0))
                return parseOcrText(outcome.text, outcome.tesseractMissing, "Photo was read with local OCR. Check and correct the draft before confirming.")
            } finally { reader.dispose() }
        }
    }

    private fun parseOcrText(text: String, tesseractMissing: Boolean, note: String): Extracted {
        val parsed = ReceiptParser.parse(text, note)
        if (parsed.edit.items.isNotEmpty() || text.isNotBlank()) return Extracted(parsed.edit, parsed.note)
        return if (tesseractMissing)
            Extracted(ReceiptEdit(), "Tesseract OCR is not available: the 'tesseract' binary was not found. Set TESSERACT_COMMAND (for example C:\\Program Files\\Tesseract-OCR\\tesseract.exe) in the backend run configuration or add Tesseract to PATH, then re-upload. The original is saved.")
        else Extracted(ReceiptEdit(), "$note No items recognized; enter them manually.")
    }

    private data class OcrOutcome(val text: String, val tesseractMissing: Boolean)

    private fun ocr(image: BufferedImage): OcrOutcome {
        if (!ocrSlots.tryAcquire()) return OcrOutcome("", false)
        try {
            val tempDir = Path.of(dataDir).toAbsolutePath().normalize().resolve("ocr-temp")
            Files.createDirectories(tempDir)
            val work = Files.createTempFile(tempDir, "receipt-ocr-", ".png")
            try {
                ImageIO.write(image, "png", work.toFile())
                // Page-segmentation mode changes a lot between tesseract versions, so run
                // several modes and keep the text that parses into the most convincing draft.
                var commandMissing = false
                val candidates = listOf(4, 6, 3).mapNotNull { psm ->
                    when (val attempt = runTesseract(work, psm)) {
                        is TesseractAttempt.Text -> score(ReceiptParser.parse(attempt.value, "Scoring pass").edit) to attempt.value
                        TesseractAttempt.CommandMissing -> { commandMissing = true; null }
                        TesseractAttempt.Failed -> null
                    }
                }
                val best = candidates.maxByOrNull { it.first }?.second.orEmpty()
                return OcrOutcome(best, commandMissing && best.isEmpty())
            } finally { Files.deleteIfExists(work) }
        } finally { ocrSlots.release() }
    }

    private sealed interface TesseractAttempt {
        data class Text(val value: String) : TesseractAttempt
        data object CommandMissing : TesseractAttempt
        data object Failed : TesseractAttempt
    }

    private fun runTesseract(work: Path, psm: Int): TesseractAttempt {
        return try {
            val output = Files.createTempFile(work.parent, "receipt-text-", ".txt")
            try {
                val process = ProcessBuilder(tesseract, work.toString(), output.toString().removeSuffix(".txt"), "-l", "eng", "--psm", psm.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                if (!process.waitFor(12, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); TesseractAttempt.Failed }
                else if (process.exitValue() == 0) TesseractAttempt.Text(Files.readString(output).take(50_000))
                else TesseractAttempt.Failed
            } finally { Files.deleteIfExists(output) }
        } catch (_: java.io.IOException) {
            TesseractAttempt.CommandMissing
        }
    }

    private fun score(edit: ReceiptEdit): Int {
        var value = edit.items.size * 1000
        if (edit.date != null) value += 500
        if (edit.store.isNotBlank() && edit.store != "Unknown") value += 200
        if (edit.total > 0) value += 100
        return value
    }

    private fun parse(text: String, note: String): Extracted =
        ReceiptParser.parse(text, note).let { Extracted(it.edit, it.note) }
}
