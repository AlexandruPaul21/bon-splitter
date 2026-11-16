package app.receiptshare

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.core.io.FileSystemResource
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files

@RestController
@RequestMapping("/api")
class ReceiptController(
    private val repository: ReceiptRepository,
    private val extraction: ReceiptExtractionService
) {
    data class GroupInput(val name: String, val currency: String)
    data class MemberInput(val name: String)

    @GetMapping("/groups")
    fun groups(): List<Group> = repository.groups()

    @PostMapping("/groups")
    fun group(@RequestBody input: GroupInput): Group = repository.createGroup(input.name, input.currency)

    @PostMapping("/groups/{groupId}/members")
    fun member(@PathVariable groupId: String, @RequestBody input: MemberInput): Group = repository.addMember(groupId, input.name)

    @DeleteMapping("/groups/{groupId}/members/{memberId}")
    fun deleteMember(@PathVariable groupId: String, @PathVariable memberId: String): Map<String, Boolean> {
        repository.deleteMember(groupId, memberId)
        return mapOf("deleted" to true)
    }

    @GetMapping("/groups/{groupId}/receipts")
    fun receipts(@PathVariable groupId: String): List<Receipt> = repository.receipts(groupId)

    @PostMapping("/groups/{groupId}/receipts")
    fun upload(@PathVariable groupId: String, @RequestParam(value = "file", required = false) file: MultipartFile?): Receipt {
        repository.group(groupId)
        if (file == null) return repository.createDraft(groupId, ReceiptEdit(), "Enter the receipt details manually.", null, null, null)
        val saved = extraction.store(file)
        return try {
            repository.createDraft(groupId, saved.extracted.edit, saved.extracted.note, saved.fileName, saved.storedName, saved.mediaType)
        } catch (error: Exception) {
            Files.deleteIfExists(extraction.path(saved.storedName))
            throw error
        }
    }

    @GetMapping("/receipts/{id}")
    fun receipt(@PathVariable id: String): Receipt = repository.receipt(id)

    @PutMapping("/receipts/{id}")
    fun update(@PathVariable id: String, @RequestBody edit: ReceiptEdit): Receipt = repository.update(id, edit)

    @DeleteMapping("/receipts/{id}")
    fun delete(@PathVariable id: String): Map<String, Boolean> {
        val storedName = repository.delete(id)
        if (storedName != null) Files.deleteIfExists(extraction.path(storedName))
        return mapOf("deleted" to true)
    }

    @PostMapping("/receipts/{id}/confirm")
    fun confirm(@PathVariable id: String): Receipt = repository.confirm(id)

    @GetMapping("/receipts/{id}/file")
    fun file(@PathVariable id: String): ResponseEntity<FileSystemResource> {
        val receipt = repository.receipt(id)
        val (storedName, mediaType) = repository.file(id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No file attached")
        val path = extraction.path(storedName)
        if (!Files.isRegularFile(path)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "File missing")
        val type = MediaType.parseMediaType(mediaType)
        val extension = when (mediaType) {
            "application/pdf" -> "pdf"
            "image/png" -> "png"
            else -> "jpg"
        }
        return ResponseEntity.ok().contentType(type).contentLength(Files.size(path))
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"${receipt.id}.$extension\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(FileSystemResource(path))
    }

    @GetMapping("/groups/{groupId}/balances")
    fun balances(@PathVariable groupId: String): GroupBalances {
        val group = repository.group(groupId)
        return SplitCalculator.balances(group.members, repository.receipts(groupId))
    }

    @PostMapping("/groups/{groupId}/receipts/paid")
    fun markPaid(@PathVariable groupId: String): Map<String, Int> {
        val paid = repository.markGroupPaid(groupId)
        return mapOf("paid" to paid)
    }
}

@RestControllerAdvice
class ApiErrorHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(error: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("message" to (error.message ?: "Invalid request")))

    @ExceptionHandler(org.springframework.dao.DataAccessException::class)
    fun constraint(@Suppress("UNUSED_PARAMETER") error: org.springframework.dao.DataAccessException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("message" to "Invalid group member or duplicate entry"))

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException::class)
    fun status(error: org.springframework.web.server.ResponseStatusException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(error.statusCode).body(mapOf("message" to (error.reason ?: "Request failed")))
}
