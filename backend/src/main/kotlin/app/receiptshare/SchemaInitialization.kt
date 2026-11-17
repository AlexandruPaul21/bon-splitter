package app.receiptshare

import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import javax.sql.DataSource

class SchemaInitialization(dataSource: DataSource) {
    init {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY)")
                val version = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { rs -> rs.next(); rs.getInt(1) }
                val migrations = mapOf(
                    1 to "db/migration/V1__initial.sql",
                    2 to "db/migration/V2__paid_status.sql",
                    3 to "db/migration/V3__item_discount_split.sql",
                    4 to "db/migration/V4__item_discounts.sql",
                    5 to "db/migration/V5__discount_included.sql",
                    6 to "db/migration/V6__whole_receipt_discounts.sql",
                    7 to "db/migration/V7__gross_item_discounts.sql"
                )
                require(version in 0..migrations.keys.max()) { "Database was created by a newer version of the app" }
                val pending = migrations.keys.filter { it > version }.sorted()
                if (pending.isNotEmpty()) {
                    statement.execute("PRAGMA foreign_keys = OFF")
                    try {
                        for (target in pending) {
                            connection.autoCommit = false
                            try {
                                ResourceDatabasePopulator(ClassPathResource(migrations.getValue(target))).populate(connection)
                                statement.executeUpdate("INSERT INTO schema_version(version) VALUES ($target)")
                                connection.commit()
                            } catch (error: Exception) {
                                connection.rollback()
                                throw error
                            } finally { connection.autoCommit = true }
                        }
                    } finally {
                        statement.execute("PRAGMA foreign_keys = ON")
                        val broken = statement.executeQuery("PRAGMA foreign_key_check").use { rs ->
                            buildList { while (rs.next()) add(rs.getString(1)) }
                        }
                        require(broken.isEmpty()) { "Foreign key violations after schema migration: $broken" }
                    }
                }
            }
        }
    }
}
