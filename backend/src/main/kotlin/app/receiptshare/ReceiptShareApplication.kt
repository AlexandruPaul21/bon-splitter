package app.receiptshare

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

@SpringBootApplication
class ReceiptShareApplication {
    @Bean
    fun dataSource(@Value("\${receipt-share.data-dir}") dataDir: String): DataSource {
        val dir = Path.of(dataDir).toAbsolutePath().normalize()
        Files.createDirectories(dir)
        val config = SQLiteConfig().apply {
            enforceForeignKeys(true)
            setBusyTimeout(5000)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
        }
        val source = SQLiteDataSource(config).apply { url = "jdbc:sqlite:${dir.resolve("receipts.sqlite")}" }
        SchemaInitialization(source)
        return source
    }
}

fun main(args: Array<String>) {
    runApplication<ReceiptShareApplication>(*args)
}
