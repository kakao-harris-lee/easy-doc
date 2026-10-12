package kr.easydoc.infrastructure.document

import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.util.UUID

class DocumentDeletionMarkerTest {
    @Test
    fun `삭제 표식은 계정 삭제 후 남고 롤백한 삭제는 남지 않는다`() {
        val db = PostgresTestSupport.createEmptyDatabase("document_deletion_marker")
        Flyway
            .configure()
            .dataSource(db.jdbcUrl, db.username, db.password)
            .load()
            .migrate()
        val owner = UUID.randomUUID()
        val workspace = UUID.randomUUID()
        val document = UUID.randomUUID()
        db.execute("INSERT INTO users(id,email,password_hash) VALUES ('$owner','marker@example.test','test')")
        db.execute("INSERT INTO workspaces(id,user_id,name) VALUES ('$workspace','$owner','marker')")
        db.execute(
            """
            INSERT INTO documents(id,user_id,workspace_id,title,source_format,source_text_encrypted,
                encryption_scheme,key_version,char_count)
            VALUES ('$document','$owner','$workspace','marker','text',decode('01','hex'),'aes256gcm-v1',1,1)
            """.trimIndent(),
        )
        db.connect().use { connection ->
            connection.autoCommit = false
            connection.createStatement().use { it.executeUpdate("DELETE FROM documents WHERE id='$document'") }
            connection.rollback()
        }
        assertThat(db.queryInt("SELECT count(*) FROM document_deletion_tombstones")).isZero()
        db.execute("DELETE FROM documents WHERE id='$document'")
        db.execute("DELETE FROM users WHERE id='$owner'")
        assertThat(db.queryInt("SELECT count(*) FROM document_deletion_tombstones WHERE id='$document'"))
            .isEqualTo(1)
        assertThat(
            db.queryInt(
                "SELECT count(*) FROM document_deletion_tombstones " +
                    "WHERE expires_at - deleted_at BETWEEN interval '8 days' - interval '1 second' " +
                    "AND interval '8 days' + interval '1 second'",
            ),
        ).isEqualTo(1)
    }
}
