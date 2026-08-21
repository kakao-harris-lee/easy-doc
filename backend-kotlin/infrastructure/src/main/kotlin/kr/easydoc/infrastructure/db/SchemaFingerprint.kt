package kr.easydoc.infrastructure.db

import java.sql.Connection
import javax.sql.DataSource

// Python 기준선 검증: `PythonSchemaBaselineTest`.

/** PostgreSQL public 스키마의 구조를 결정적인 텍스트로 뽑는다. */
object SchemaFingerprint {
    /** 지문 질의. */
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

    /** 저장소에 기록된 Python 기준선 지문. */
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

    /** 두 지문의 차이를 사람이 읽을 수 있게 만든다. */
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
