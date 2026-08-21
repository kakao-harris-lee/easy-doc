package kr.easydoc.infrastructure.crypto

import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.infrastructure.DatabaseHandle
import kr.easydoc.infrastructure.MigrationCatalog
import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.SQLException

/** 코드 상수와 스키마 CHECK 가 같은 값을 말하는가 — `migration-safety-gate` I-7 검증 5. */
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

    @Test
    @DisplayName("X8 V4 — `key_version` 이 0 이하인 행은 저장되지 않는다 (두 테이블 모두)")
    fun `키 세대 도메인이 제약을 지난다`() {
        database.execute(documentInsert(DOCUMENT_VERSION_OK, EncryptionScheme.AES_256_GCM_V1, keyVersion = 1))

        listOf("0" to 0, "음수" to -1).forEach { (label, version) ->
            assertThatThrownBy {
                database.execute(documentInsert(DOCUMENT_VERSION_BAD, EncryptionScheme.AES_256_GCM_V1, version))
            }.describedAs("documents 에 key_version=%s(%d) 가 들어갔다", label, version)
                .isInstanceOf(SQLException::class.java)

            assertThatThrownBy {
                database.execute(
                    conversionInsert(
                        CONVERSION_VERSION_BAD,
                        DOCUMENT_VERSION_OK,
                        EncryptionScheme.AES_256_GCM_V1,
                        version,
                    ),
                )
            }.describedAs("conversions 에 key_version=%s(%d) 가 들어갔다 — 한 테이블만 고친 회귀다", label, version)
                .isInstanceOf(SQLException::class.java)
        }
    }

    @Test
    @DisplayName("V3 SQL 리터럴이 코드 상수와 같다 — 적용된 DB 만 보면 안 잡히는 축")
    fun `마이그레이션 리터럴이 코드 상수와 같다`() {
        val sql = MigrationCatalog.sourceOf("3")

        assertThat(sql)
            .describedAs("V3 가 코드 상수를 CHECK 목록에 넣지 않는다")
            .contains("IN ('${EncryptionScheme.AES_256_GCM_V1}')")
    }

    @Test
    @DisplayName("X10 `EncryptedField.wireName` 이 실제 컬럼을 가리킨다 — 양방향으로 대조한다")
    fun `결속 이름이 실제 컬럼과 일치한다`() {
        val declared = EncryptedField.entries.map { it.wireName }.toSet()
        val actual = encryptedColumnsInSchema()

        assertThat(declared)
            .describedAs("`EncryptedField` 의 wireName 이 서로 겹친다 — 두 컬럼이 같은 자리로 결속된다")
            .hasSize(EncryptedField.entries.size)
        assertThat(actual)
            .describedAs("스키마에서 bytea 암호문 컬럼을 하나도 찾지 못했다 — 이 대조가 0건을 훑고 통과한다")
            .isNotEmpty()
        assertThat(declared)
            .withFailMessage {
                "결속 이름과 실제 컬럼이 갈렸다:\n" +
                    "  선언에만 있다: ${(declared - actual).sorted()}\n" +
                    "  스키마에만 있다: ${(actual - declared).sorted()}\n" +
                    "  선언에만 있으면 그 이름으로 쓴 암호문은 **영원히 열리지 않는다**(AAD 불일치).\n" +
                    "  스키마에만 있으면 새 암호문 컬럼이 결속 없이 생긴 것이다 — `EncryptedField` 에 더하라."
            }.isEqualTo(actual)
    }

    /** `public` 스키마의 모든 bytea 컬럼을 `테이블.컬럼` 으로. 열거하지 않고 DB 에서 읽는다. */
    private fun encryptedColumnsInSchema(): Set<String> =
        database.connect().use { connection ->
            connection.prepareStatement(BYTEA_COLUMNS_SQL).use { statement ->
                statement.executeQuery().use { rows ->
                    buildSet { while (rows.next()) add("${rows.getString(1)}.${rows.getString(2)}") }
                }
            }
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

        /**
         * 암호문이 들어갈 수 있는 컬럼 전부. 이름 규칙이 아니라 타입으로 고른다 —
         * `%_encrypted` 로 고르면 이름을 안 지킨 컬럼이 조용히 빠진다.
         * Flyway 자신의 이력 테이블은 `public` 에 있지만 bytea 컬럼이 없어 걸리지 않는다.
         */
        const val BYTEA_COLUMNS_SQL =
            """
            SELECT table_name, column_name
            FROM information_schema.columns
            WHERE table_schema = 'public' AND data_type = 'bytea'
            """

        const val USER_ID = "11111111-1111-1111-1111-111111111111"
        const val WORKSPACE_ID = "22222222-2222-2222-2222-222222222222"
        const val DOCUMENT_ACCEPTED = "33333333-3333-3333-3333-333333333333"
        const val DOCUMENT_REJECTED = "44444444-4444-4444-4444-444444444444"
        const val CONVERSION_ACCEPTED = "55555555-5555-5555-5555-555555555555"
        const val CONVERSION_REJECTED = "66666666-6666-6666-6666-666666666666"
        const val DOCUMENT_VERSION_OK = "77777777-7777-7777-7777-777777777777"
        const val DOCUMENT_VERSION_BAD = "88888888-8888-8888-8888-888888888888"
        const val CONVERSION_VERSION_BAD = "99999999-9999-9999-9999-999999999999"

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
            keyVersion: Int = 1,
        ): String =
            """
            INSERT INTO documents
                (id, user_id, title, source_format, source_text_encrypted, char_count, workspace_id,
                 encryption_scheme, key_version)
            VALUES ('$id', '$USER_ID', '안내문', 'docx', '\x00'::bytea, 10, '$WORKSPACE_ID', '$scheme', $keyVersion);
            """.trimIndent()

        fun conversionInsert(
            id: String,
            documentId: String,
            scheme: String,
            keyVersion: Int = 1,
        ): String =
            """
            INSERT INTO conversions (id, document_id, encryption_scheme, key_version)
            VALUES ('$id', '$documentId', '$scheme', $keyVersion);
            """.trimIndent()
    }
}
