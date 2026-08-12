package kr.easydoc.infrastructure.db

import java.sql.Connection
import javax.sql.DataSource

/**
 * PostgreSQL public 스키마의 구조를 결정적인 텍스트로 뽑는다.
 *
 * 계획 §4.2-2·§4.2-4가 요구하는 두 가지를 같은 도구로 처리하기 위한 것이다.
 *
 * 1. **V1이 Alembic 결과를 정말 재현하는가** — 빈 DB에 V1만 적용한 지문이
 *    Alembic `0001~0006` 을 적용한 지문과 같아야 한다. `PythonSchemaBaselineTest` 가 본다.
 * 2. **기존 DB를 baseline 해도 되는가** — Flyway 이력이 없는 기존 스키마의 지문이
 *    기준선과 같을 때만 baseline 을 기록한다. [FlywayBaselineGuard] 가 본다.
 *
 * 해시가 아니라 **여러 줄 텍스트**를 쓴다. 해시는 "다르다"만 알려주지만 텍스트는
 * 어디가 다른지 diff 로 바로 보여준다. 스키마가 어긋난 상황은 대개 급할 때 발견되고,
 * 그때 필요한 것은 값이 아니라 원인이다.
 *
 * ## 제외 대상
 *
 * `alembic_version` 과 `flyway_schema_history` 는 지문에서 뺀다. 둘은 마이그레이션 도구의
 * 장부이지 애플리케이션 스키마가 아니고, 두 경로(Alembic 적용 / Flyway 적용)에서
 * 정확히 한쪽에만 존재하므로 넣으면 지문이 절대 일치할 수 없다.
 */
object SchemaFingerprint {
    /**
     * 지문 질의.
     *
     * 컬럼 **서수**(`a.attnum`)까지 담는 이유: Alembic 은 `0004`·`0006` 에서 `ADD COLUMN`
     * 으로 컬럼을 붙였고 그 컬럼들이 뒤쪽 서수를 갖는다. 서수를 빼면 V1 이 컬럼을 다른
     * 순서로 선언해도 지문이 같아져, `SELECT *` 순서와 COPY 형식이 조용히 달라진다.
     */
    private val FINGERPRINT_SQL =
        """
        WITH t AS (
            SELECT c.oid, c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relkind = 'r'
              AND c.relname NOT IN ('alembic_version', 'flyway_schema_history')
        )
        SELECT line FROM (
            SELECT 1 AS grp, 'extension ' || extname AS line
              FROM pg_extension WHERE extname <> 'plpgsql'
            UNION ALL
            SELECT 2, 'table ' || t.relname FROM t
            UNION ALL
            SELECT 3, 'column ' || t.relname || ' ' || a.attnum || ' ' || a.attname
                      || ' ' || format_type(a.atttypid, a.atttypmod)
                      || (CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE ' NULL' END)
                      || ' default=' || COALESCE(pg_get_expr(d.adbin, d.adrelid), '-')
              FROM t
              JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum > 0 AND NOT a.attisdropped
              LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            UNION ALL
            SELECT 4, 'constraint ' || t.relname || ' ' || con.conname || ' '
                      || pg_get_constraintdef(con.oid)
              FROM t JOIN pg_constraint con ON con.conrelid = t.oid
            UNION ALL
            SELECT 5, 'index ' || t.relname || ' ' || ic.relname || ' '
                      || pg_get_indexdef(i.indexrelid)
              FROM t
              JOIN pg_index i ON i.indrelid = t.oid
              JOIN pg_class ic ON ic.oid = i.indexrelid
        ) s
        ORDER BY grp, line
        """.trimIndent()

    /** 애플리케이션 테이블이 하나라도 있는지. baseline 판단의 첫 갈래다. */
    private const val USER_TABLE_COUNT_SQL = """
        SELECT count(*) FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relkind = 'r'
          AND c.relname NOT IN ('alembic_version', 'flyway_schema_history')
    """

    /** 지문을 계산한다. 줄 끝 개행 포함, 줄 순서 결정적. */
    fun of(dataSource: DataSource): String = dataSource.connection.use { of(it) }

    /** 이미 열린 커넥션에서 지문을 계산한다. */
    fun of(connection: Connection): String {
        val lines = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery(FINGERPRINT_SQL).use { rows ->
                while (rows.next()) {
                    lines += rows.getString(1)
                }
            }
        }
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    /** `alembic_version`·`flyway_schema_history` 를 뺀 애플리케이션 테이블 수. */
    fun userTableCount(connection: Connection): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery(USER_TABLE_COUNT_SQL).use { rows ->
                if (rows.next()) rows.getInt(1) else 0
            }
        }

    /**
     * 저장소에 기록된 Python 기준선 지문.
     *
     * 이 파일은 빈 DB에 `uv run alembic upgrade head` 를 실제로 돌려 뽑았다.
     * 사람이 손으로 적은 값이 아니다. `#` 로 시작하는 줄은 생성 절차를 적은 주석이라
     * 지문에서 뺀다.
     */
    fun expectedPythonBaseline(): String {
        val resource =
            SchemaFingerprint::class.java
                .getResourceAsStream("/db/baseline/python-schema-fingerprint.txt")
                ?: error("db/baseline/python-schema-fingerprint.txt 리소스를 찾지 못했다")
        val text = resource.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return text
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .joinToString(separator = "\n", postfix = "\n")
    }

    /**
     * 두 지문의 차이를 사람이 읽을 수 있게 만든다.
     *
     * 순서가 결정적이라 집합 차이만으로 충분하다 — 어느 줄이 없어졌고 어느 줄이 늘었는지가
     * 곧 원인이다.
     */
    fun describeDifference(
        expected: String,
        actual: String,
    ): String {
        val expectedLines = expected.lines().filter { it.isNotBlank() }.toSet()
        val actualLines = actual.lines().filter { it.isNotBlank() }.toSet()
        val missing = (expectedLines - actualLines).sorted()
        val unexpected = (actualLines - expectedLines).sorted()
        return buildString {
            if (missing.isNotEmpty()) {
                appendLine("기준선에는 있는데 실제 DB에 없는 것 (${missing.size}건):")
                missing.forEach { appendLine("  - $it") }
            }
            if (unexpected.isNotEmpty()) {
                appendLine("실제 DB에만 있는 것 (${unexpected.size}건):")
                unexpected.forEach { appendLine("  + $it") }
            }
            if (missing.isEmpty() && unexpected.isEmpty()) {
                appendLine("줄 집합은 같다 (차이는 줄 순서뿐).")
            }
        }
    }
}
