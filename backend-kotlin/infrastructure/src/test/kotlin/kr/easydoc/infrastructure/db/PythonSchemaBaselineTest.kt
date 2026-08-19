package kr.easydoc.infrastructure.db

import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.sql.SQLException

/**
 * `V1__python_schema_baseline.sql` 이 Alembic `0001~0006` 의 결과를 정말 재현하는지 본다.
 *
 * 계획 §4.2-2가 "Alembic 0001~0006의 기대 스키마와 **실제** 스키마를 비교한다. README에
 * 0003 제자리 수정 이력이 있으므로 파일만 믿지 않는다"고 요구한 대조를 회귀 테스트로
 * 고정한 것이다.
 *
 * 기준값(`db/baseline/python-schema-fingerprint.txt`)은 빈 DB에
 * `uv run alembic upgrade head` 를 실제로 돌려 뽑았다 — 사람이 마이그레이션 파일을 읽고
 * 옮겨 적은 값이 아니다.
 *
 * 이 테스트가 통과하면 이후 테스트들이 "V1 적용 = Alembic 적용"으로 취급해도 된다.
 */
class PythonSchemaBaselineTest {
    @Test
    @DisplayName("V1만 적용한 스키마가 Alembic 0006 결과와 같다")
    fun `V1 이 Alembic 스키마를 재현한다`() {
        val database = PostgresTestSupport.createEmptyDatabase("baseline_v1")

        // target=1: V2(encryption_scheme)를 적용하지 않는다. 기준선은 "Python이 만든 스키마"이고
        // V2는 Kotlin 전용 추가라 여기 포함되면 안 된다.
        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .target("1")
            .load()
            .migrate()

        val actual = database.connect().use { SchemaFingerprint.of(it) }
        val expected = SchemaFingerprint.expectedPythonBaseline()

        assertThat(actual)
            .withFailMessage {
                buildString {
                    appendLine("V1 이 Alembic 스키마를 재현하지 못한다.")
                    appendLine()
                    append(SchemaFingerprint.describeDifference(expected, actual))
                }
            }.isEqualTo(expected)
    }

    @Test
    @DisplayName("V1 은 alembic_version 을 만들지 않는다")
    fun `V1 은 alembic_version 을 만들지 않는다`() {
        // 계획 §4.2-7: alembic_version 은 Alembic의 소유물이고 Kotlin이 수정하지 않는다.
        // 만들지 않는 것도 그 규칙의 일부다 — Flyway가 만든 빈 장부가 있으면 나중에
        // Python 롤백 시 Alembic이 "0006까지 적용됨"이 아니라 "미적용"으로 읽는다.
        val database = PostgresTestSupport.createEmptyDatabase("baseline_no_alembic")

        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val tables =
            database.connect().use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                                "WHERE n.nspname = 'public' AND c.relkind = 'r'",
                        ).use { rows ->
                            generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                        }
                }
            }

        assertThat(tables).doesNotContain("alembic_version")
        assertThat(tables).contains("users", "workspaces", "documents", "conversions")
    }

    @Test
    @DisplayName("전 버전을 적용해도 Python 컬럼이 하나도 사라지지 않는다 (additive)")
    fun `마이그레이션은 기존 컬럼을 지우지 않는다`() {
        val database = PostgresTestSupport.createEmptyDatabase("baseline_additive")

        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val fingerprint = database.connect().use { SchemaFingerprint.of(it) }

        // V2 가 더한 컬럼은 두 테이블 모두에 있다(Phase 0 필수 조치 D).
        assertThat(fingerprint).contains("column documents 11 encryption_scheme character varying(16) NOT NULL")
        assertThat(fingerprint).contains("column conversions 17 encryption_scheme character varying(16) NOT NULL")

        // additive 규칙: 기존 컬럼이 하나도 사라지지 않았다.
        //
        // **줄 전체가 아니라 컬럼 이름·서수·타입으로 대조한다.** V3 가 `key_version` 의
        // DEFAULT 를 없애 그 두 줄의 `default=` 조각이 달라졌기 때문이다. 계획 §4.2 가
        // 금지한 것은 컬럼을 지우거나 이름을 바꾸거나 타입을 좁히는 것이고, DEFAULT 는
        // 그 목록에 없다 — 여기서 재는 축을 그 조항에 맞춘다.
        val baselineColumns =
            SchemaFingerprint
                .expectedPythonBaseline()
                .lines()
                .filter { it.startsWith("column ") }
                .map { it.substringBefore(" default=") }
        assertThat(fingerprint.lines().map { it.substringBefore(" default=") }).containsAll(baselineColumns)
    }

    @Test
    @DisplayName("V3 는 encryption_scheme·key_version 의 DEFAULT 를 없앤다 — 값을 적지 않은 쓰기가 실패한다")
    fun `방식과 키 세대를 적지 않은 INSERT 는 실패한다`() {
        // ## 이 케이스가 대체한 것
        //
        // 여기 있던 것은 *"V2 적용 후에도 Python 이 쓰던 INSERT 가 그대로 동작한다"* 였고,
        // 근거는 **Phase 7 관찰 기간의 롤백 조건**이었다. 2026-08-12 결정으로 Python 은
        // 폐기 대상이 되고 롤백이 사라져(master-plan 6.2 · §9 결정 2·3) 그 요구가 없어졌다.
        //
        // 자리를 비우지 않고 **정반대 성질**을 넣는다. privacy-gate 03 §5-4 가 지목한 위험이
        // 정확히 이것이었다 — 컬럼을 명시하지 않은 INSERT 에 DEFAULT 가 조용히 값을 채우면
        // 그 값이 데이터에 대해 거짓이 된다(암호문은 AEAD 인데 이름은 Fernet). DEFAULT 를
        // 없앴으므로 이제 그런 INSERT 는 NOT NULL 위반으로 즉시 실패해야 한다.
        val database = PostgresTestSupport.createEmptyDatabase("baseline_no_default")

        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(OWNER_ROWS_SQL)
            }

            assertThatThrownBy {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(DOCUMENT_WITHOUT_CRYPTO_COLUMNS_SQL)
                }
            }.describedAs("DEFAULT 가 남아 있어 거짓 방식 이름·키 세대가 조용히 채워졌다")
                .isInstanceOf(SQLException::class.java)
                // 두 컬럼 중 **어느 쪽을 먼저 지목하든** 통과다. PostgreSQL 은 NOT NULL 위반을
                // 만나는 즉시 끊으므로 컬럼 서수(key_version 6 · encryption_scheme 11)에 따라
                // 메시지가 갈린다 — 어느 하나를 못박으면 컬럼 순서에 묶인 단언이 된다.
                .satisfies({ failure -> assertThat(failure.message).containsAnyOf("encryption_scheme", "key_version") })
        }
    }

    private companion object {
        /** 문서 행이 참조해야 하는 사용자·작업 공간. 암호화와 무관한 배경이다. */
        val OWNER_ROWS_SQL =
            """
            INSERT INTO users (id, email, password_hash)
            VALUES ('11111111-1111-1111-1111-111111111111', 'a@example.kr', 'phc');
            INSERT INTO workspaces (id, user_id, name)
            VALUES ('22222222-2222-2222-2222-222222222222',
                    '11111111-1111-1111-1111-111111111111', '기본 작업 공간');
            """.trimIndent()

        /** `encryption_scheme`·`key_version` 을 빠뜨린 INSERT. V3 이후로는 실패해야 한다. */
        val DOCUMENT_WITHOUT_CRYPTO_COLUMNS_SQL =
            """
            INSERT INTO documents
                (id, user_id, title, source_format, source_text_encrypted, char_count, workspace_id)
            VALUES ('33333333-3333-3333-3333-333333333333',
                    '11111111-1111-1111-1111-111111111111', '안내문', 'docx',
                    '\x00'::bytea, 10, '22222222-2222-2222-2222-222222222222');
            """.trimIndent()
    }
}
