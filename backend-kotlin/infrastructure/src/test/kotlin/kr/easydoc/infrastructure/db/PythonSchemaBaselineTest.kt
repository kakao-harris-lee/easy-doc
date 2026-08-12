package kr.easydoc.infrastructure.db

import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

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
    @DisplayName("V2 는 encryption_scheme 을 additive 로 추가한다")
    fun `V2 가 encryption_scheme 을 기본값과 함께 추가한다`() {
        val database = PostgresTestSupport.createEmptyDatabase("baseline_v2")

        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val fingerprint = database.connect().use { SchemaFingerprint.of(it) }

        // Phase 0 필수 조치 D — 대상은 documents·conversions 둘이고 기본값은 'fernet-v1'.
        assertThat(fingerprint).contains(
            "column documents 11 encryption_scheme character varying(16) NOT NULL " +
                "default='fernet-v1'::character varying",
        )
        assertThat(fingerprint).contains(
            "column conversions 17 encryption_scheme character varying(16) NOT NULL " +
                "default='fernet-v1'::character varying",
        )

        // additive 규칙: 기존 컬럼이 하나도 사라지지 않았다.
        val baselineColumns =
            SchemaFingerprint
                .expectedPythonBaseline()
                .lines()
                .filter { it.startsWith("column ") }
        assertThat(fingerprint.lines()).containsAll(baselineColumns)
    }

    @Test
    @DisplayName("V2 적용 후에도 Python 이 쓰던 INSERT 가 그대로 동작한다")
    fun `Python 컬럼만 지정한 INSERT 가 성공한다`() {
        // Phase 7 관찰 기간의 롤백 조건 — Kotlin이 만든 스키마 위에서 Python이 이어서 쓸 수
        // 있어야 한다. SQLAlchemy 모델에 없는 encryption_scheme 은 INSERT 문에 나타나지
        // 않으므로 DEFAULT 가 채워야 한다.
        val database = PostgresTestSupport.createEmptyDatabase("baseline_python_write")

        Flyway
            .configure()
            .dataSource(database.jdbcUrl, database.username, database.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        database.connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    INSERT INTO users (id, email, password_hash)
                    VALUES ('11111111-1111-1111-1111-111111111111', 'a@example.kr', 'phc');
                    INSERT INTO workspaces (id, user_id, name)
                    VALUES ('22222222-2222-2222-2222-222222222222',
                            '11111111-1111-1111-1111-111111111111', '기본 작업 공간');
                    INSERT INTO documents
                        (id, user_id, title, source_format, source_text_encrypted, char_count, workspace_id)
                    VALUES ('33333333-3333-3333-3333-333333333333',
                            '11111111-1111-1111-1111-111111111111', '안내문', 'docx',
                            '\x00'::bytea, 10, '22222222-2222-2222-2222-222222222222');
                    """.trimIndent(),
                )
            }

            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "SELECT encryption_scheme, key_version FROM documents",
                    ).use { rows ->
                        assertThat(rows.next()).isTrue()
                        assertThat(rows.getString("encryption_scheme")).isEqualTo("fernet-v1")
                        assertThat(rows.getInt("key_version")).isEqualTo(1)
                    }
            }
        }
    }
}
