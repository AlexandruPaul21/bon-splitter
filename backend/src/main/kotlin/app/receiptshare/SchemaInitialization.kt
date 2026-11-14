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
                require(version in 0..1) { "Database was created by a newer version of the app" }
                if (version == 0) {
                    connection.autoCommit = false
                    try {
                        ResourceDatabasePopulator(ClassPathResource("db/migration/V1__initial.sql")).populate(connection)
                        statement.executeUpdate("INSERT INTO schema_version(version) VALUES (1)")
                        connection.commit()
                    } catch (error: Exception) {
                        connection.rollback()
                        throw error
                    } finally { connection.autoCommit = true }
                }
            }
        }
    }
}
