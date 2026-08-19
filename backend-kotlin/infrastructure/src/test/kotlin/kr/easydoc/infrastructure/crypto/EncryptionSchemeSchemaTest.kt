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

    @Test
    @DisplayName("X8 V4 — `key_version` 이 0 이하인 행은 저장되지 않는다 (두 테이블 모두)")
    fun `키 세대 도메인이 제약을 지난다`() {
        // 실측으로 열려 있던 자리다(privacy-gate F-4): `key_version = -1` 로 INSERT 가
        // 성공했다. 그 행의 암호문은 설정에 있을 수 없는 세대를 가리키므로 열리지 않는다.
        // 코드 쪽 검증(`CryptoConfiguration`·`EncryptedContent`)은 발견 시점을 당길 뿐이고,
        // 앱을 거치지 않는 쓰기까지 막는 마지막 방어선이 여기다.
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
        // 위 케이스는 **적용된 DB** 의 CHECK 를 읽는다. 그것만으로는 스크립트에 적힌 리터럴이
        // 코드 상수와 같은지 알 수 없고, 갈리면 다음 마이그레이션을 쓸 때 어느 쪽을 베낄지가
        // 갈린다. 그래서 파일 원문을 직접 읽어 대조한다.
        val sql = MigrationCatalog.sourceOf("3")

        assertThat(sql)
            .describedAs("V3 가 코드 상수를 CHECK 목록에 넣지 않는다")
            .contains("IN ('${EncryptionScheme.AES_256_GCM_V1}')")
    }

    @Test
    @DisplayName("X10 `EncryptedField.wireName` 이 실제 컬럼을 가리킨다 — 양방향으로 대조한다")
    fun `결속 이름이 실제 컬럼과 일치한다`() {
        // ## 왜 이 대조가 필요한가 (게이트 25 X10)
        //
        // `wireName` 은 AEAD 의 associated data 에 실린다. 문자열을 한 글자 바꾸면 **이미 저장된
        // 모든 행이 영원히 열리지 않는다.** 그런데 이 문자열을 지키는 장치는 KDoc 의 「이름만
        // 다듬는 변경을 하지 않는다」 한 줄뿐이었고, 실제로 바꿔 보면 729건 전건이 초록이었다.
        // 산문은 **범위 선언형**이라 빈 선언에서 통과한다(`CLAUDE.md` 규칙 4).
        //
        // 두 방향을 함께 본다. 한 방향만 보면 각각 다른 것이 새어 나간다 —
        //   선언 → 스키마 : 이름을 바꾸거나 오타를 내면 가리키는 컬럼이 없어진다.
        //   스키마 → 선언 : 새 암호문 컬럼이 생겼는데 아무도 결속하지 않은 상태를 잡는다.
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

    /** `public` 스키마의 **모든 bytea 컬럼**을 `테이블.컬럼` 으로. 열거하지 않고 DB 에서 읽는다. */
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
         * 암호문이 들어갈 수 있는 컬럼 전부. **이름 규칙이 아니라 타입**으로 고른다 —
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
