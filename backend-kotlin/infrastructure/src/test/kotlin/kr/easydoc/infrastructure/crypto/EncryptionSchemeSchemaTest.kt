package kr.easydoc.infrastructure.crypto

import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException

/**
 * **코드 상수와 스키마 CHECK 가 같은 값을 말하는가** — `migration-safety-gate` I-7 검증 5.
 *
 * ## 왜 따로 재는가
 *
 * `EncryptionScheme.AES_256_GCM_V1` 과 `V3__encryption_scheme_aead.sql` 의 CHECK 목록은
 * **같은 사실을 두 곳에 적은 것**이다. 두 곳에 적힌 사실은 갈린다 — 이 저장소가 반복해
 * 고쳐 온 형태다. 갈리면 첫 INSERT 가 제약 위반으로 죽는데, 그 시점은 Phase 4 의 문서
 * 저장 단위이고 원인은 이 파일과 저 파일 사이에 있다.
 *
 * 그래서 **DB 를 실제로 띄워** 상수로 쓰고 읽는다. 갈리는 순간 여기가 빨개진다.
 *
 * ## `fernet-v1` 이 거부되는지도 함께 본다
 *
 * privacy-gate 03 §5-4 의 해제 조건 ⑴ 이 *"CHECK 도메인을 넓히고 DEFAULT 를 바꾸거나
 * 없앤다"* 였다. 새 값이 통과하는 것만 재면 **옛 값도 함께 통과하는 상태**를 구분하지
 * 못한다 — I-7 이 금지한 것은 정확히 그 값이므로 거부까지 재야 조건이 닫힌다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EncryptionSchemeSchemaTest {
    private val database: DatabaseHandle by lazy {
        PostgresTestSupport.createEmptyDatabase("encryption_scheme").also { handle ->
            Flyway
                .configure()
                .dataSource(handle.jdbcUrl, handle.username, handle.password)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            handle.execute(OWNER_ROWS_SQL)
        }
    }

    @Test
    @DisplayName("두 테이블의 CHECK 가 코드 상수만 허용한다 (fernet-v1 은 목록에 없다)")
    fun `CHECK 도메인이 코드 상수와 같다`() {
        listOf("ck_documents_encryption_scheme_valid", "ck_conversions_encryption_scheme_valid").forEach { name ->
            val definition = constraintDefinition(name)

            assertThat(definition)
                .describedAs("%s 가 코드 상수를 허용하지 않는다", name)
                .contains(EncryptionScheme.AES_256_GCM_V1)
            assertThat(definition)
                .describedAs("%s 가 아직 Fernet 이름을 허용한다 — I-7 검증 5 위반", name)
                .doesNotContain("fernet")
        }
    }

    @Test
    @DisplayName("코드 상수로는 쓸 수 있고, 다른 방식 이름으로는 쓸 수 없다")
    fun `방식 이름이 제약을 지난다`() {
        database.execute(documentInsert(DOCUMENT_ACCEPTED, EncryptionScheme.AES_256_GCM_V1))

        assertThatThrownBy { database.execute(documentInsert(DOCUMENT_REJECTED, "fernet-v1")) }
            .describedAs("Fernet 이름이 여전히 들어간다")
            .isInstanceOf(SQLException::class.java)

        // conversions 쪽도 같은 제약을 진다 — 두 테이블 중 하나만 고치는 회귀를 막는다.
        database.execute(conversionInsert(CONVERSION_ACCEPTED, DOCUMENT_ACCEPTED, EncryptionScheme.AES_256_GCM_V1))
        assertThatThrownBy {
            database.execute(conversionInsert(CONVERSION_REJECTED, DOCUMENT_ACCEPTED, "fernet-v1"))
        }.isInstanceOf(SQLException::class.java)
    }

    @Test
    @DisplayName("방식 이름이 컬럼 폭(varchar 16)에 들어간다")
    fun `방식 이름이 컬럼에 들어간다`() {
        assertThat(EncryptionScheme.AES_256_GCM_V1.length)
            .describedAs("이름이 컬럼 폭을 넘으면 INSERT 가 잘리거나 실패한다")
            .isLessThanOrEqualTo(SCHEME_COLUMN_WIDTH)
    }

    private fun constraintDefinition(name: String): String =
        database.connect().use { connection ->
            connection.prepareStatement("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?").use {
                it.setString(1, name)
                it.executeQuery().use { rows ->
                    check(rows.next()) { "제약 $name 이 없다 — V3 가 적용되지 않았거나 이름이 바뀌었다" }
                    rows.getString(1)
                }
            }
        }

    private companion object {
        const val SCHEME_COLUMN_WIDTH = 16

        const val USER_ID = "11111111-1111-1111-1111-111111111111"
        const val WORKSPACE_ID = "22222222-2222-2222-2222-222222222222"
        const val DOCUMENT_ACCEPTED = "33333333-3333-3333-3333-333333333333"
        const val DOCUMENT_REJECTED = "44444444-4444-4444-4444-444444444444"
        const val CONVERSION_ACCEPTED = "55555555-5555-5555-5555-555555555555"
        const val CONVERSION_REJECTED = "66666666-6666-6666-6666-666666666666"

        val OWNER_ROWS_SQL =
            """
            INSERT INTO users (id, email, password_hash)
            VALUES ('$USER_ID', 'scheme@example.kr', 'phc');
            INSERT INTO workspaces (id, user_id, name)
            VALUES ('$WORKSPACE_ID', '$USER_ID', '기본 작업 공간');
            """.trimIndent()

        fun documentInsert(
            id: String,
            scheme: String,
        ): String =
            """
            INSERT INTO documents
                (id, user_id, title, source_format, source_text_encrypted, char_count, workspace_id,
                 encryption_scheme, key_version)
            VALUES ('$id', '$USER_ID', '안내문', 'docx', '\x00'::bytea, 10, '$WORKSPACE_ID', '$scheme', 1);
            """.trimIndent()

        fun conversionInsert(
            id: String,
            documentId: String,
            scheme: String,
        ): String =
            """
            INSERT INTO conversions (id, document_id, encryption_scheme, key_version)
            VALUES ('$id', '$documentId', '$scheme', 1);
            """.trimIndent()
    }
}
