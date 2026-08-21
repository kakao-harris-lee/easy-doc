package kr.easydoc.infrastructure.db

import kr.easydoc.infrastructure.PostgresTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.sql.SQLException

/** `V1__python_schema_baseline.sql` 이 Alembic `0001~0006` 의 결과를 정말 재현하는지 본다. */
class PythonSchemaBaselineTest {
    @Test
    @DisplayName("V1만 적용한 스키마가 Alembic 0006 결과와 같다")
    fun `V1 이 Alembic 스키마를 재현한다`() {
        val database = PostgresTestSupport.createEmptyDatabase("baseline_v1")

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

        assertThat(fingerprint).contains("column documents 11 encryption_scheme character varying(16) NOT NULL")
        assertThat(fingerprint).contains("column conversions 17 encryption_scheme character varying(16) NOT NULL")

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
