package kr.easydoc.infrastructure.db

import kr.easydoc.infrastructure.DatabaseHandle
import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Flyway 기동 경로가 실제로 실행한 SQL 을 **스레드·시각과 함께** 기록하는 탐침.
 *
 * ## 왜 이것이 필요한가
 *
 * 리뷰 T-A⑵(`15_phase3-preflight_migration-reviewer.md`)가 지적한 자리다. 종전
 * `동시 기동이 직렬화된다` 테스트의 단언은 **"예외가 안 났음"**(`failures.isEmpty()`)
 * 하나였고, 그것은 잠금이 없어도 성립한다. 실제로 측정했다 — 일회용 worktree 에서
 * [FlywayBaselineGuard] 의 `withAdvisoryLock` 을 `run` 으로 바꾸고 돌렸더니
 * **9 tests / 0 failures 로 4회 연속 초록**이었다. 즉 그 테스트는 잠금을 검증하지
 * 않으면서 검증한다고 집계되고 있었다.
 *
 * 원인은 대리 지표다. Flyway 자신이 이력 테이블에 잠금을 걸기 때문에 `baseline()`·
 * `migrate()` 구간은 우리 잠금이 없어도 알아서 직렬화되고, 중복 baseline 도 예외 없이
 * 넘어간다. 우리 잠금이 지키는 것은 그 구간이 아니라 **"읽고 판정한 것과 기록하는 것이
 * 같은 스키마"** 이므로, 판정 구간이 겹치는지를 직접 재야 한다.
 *
 * ## 무엇을 재는가
 *
 * [TracingDataSource] 를 지나 열린 커넥션에서 실행된 SQL 의 (스레드, 시작 시각, 종료
 * 시각)을 남긴다 — **"실행된 모든 SQL" 이 아니라 그 경로의 것**이고, 정확한 도달 범위와
 * 그 밖에 남는 두 자리는 [TracingDataSource] 에 적었다. 여기서 **동기화 문장을 걷어낸
 * 나머지**가 임계 구간이다 — 잠금을 기다리는 시간은 임계 구간이 아니고, 잠금 안에서
 * 하는 일이 임계 구간이다. 잠금을 떼면 동기화 문장이 아예 사라지고 판정 질의들이
 * 그대로 겹쳐 드러난다.
 *
 * 스레드 귀속이 성립하는 근거: Flyway 는 `info()`·`baseline()`·`migrate()` 를 **호출한
 * 스레드에서** 실행하고 커넥션도 그 스레드에서 열고 닫는다.
 */
class MigrationStatementTracer {
    private val executed = ConcurrentLinkedQueue<ExecutedStatement>()

    internal fun record(
        sql: String,
        startedAtNanos: Long,
        endedAtNanos: Long,
    ) {
        executed +=
            ExecutedStatement(
                thread = Thread.currentThread().name,
                sql = sql,
                startedAtNanos = startedAtNanos,
                endedAtNanos = endedAtNanos,
            )
    }

    /** 잠금 획득·해제를 뺀, 실제로 스키마를 읽고 쓴 문장들. */
    fun criticalStatements(): List<ExecutedStatement> = executed.toList().filterNot { it.isSynchronization }

    /**
     * 스레드마다 임계 구간에 머문 구간 하나. 시작 시각 오름차순이다.
     *
     * 한 문장도 실행하지 않은 스레드는 창이 없다 — 그래서 호출부가 **창의 개수를 먼저
     * 단언한다**(탐침이 죽으면 겹침 0 으로 조용히 통과하는 것을 막는다).
     */
    fun criticalWindows(): List<CriticalWindow> =
        criticalStatements()
            .groupBy { it.thread }
            .map { (thread, rows) ->
                CriticalWindow(
                    thread = thread,
                    startedAtNanos = rows.minOf { it.startedAtNanos },
                    endedAtNanos = rows.maxOf { it.endedAtNanos },
                    statementCount = rows.size,
                )
            }.sortedBy { it.startedAtNanos }

    /**
     * 겹치는 구간 쌍. 하나라도 있으면 두 기동이 동시에 판정·기록에 들어갔다는 뜻이다.
     *
     * 시작 시각으로 정렬한 구간들은 **이웃만 비교하면 충분하다** — `start[i+2] < end[i]`
     * 이면 `start[i+1] <= start[i+2] < end[i]` 라 이웃 쌍에서 먼저 걸린다.
     */
    fun overlappingWindows(): List<Pair<CriticalWindow, CriticalWindow>> =
        criticalWindows()
            .zipWithNext()
            .filter { (earlier, later) -> later.startedAtNanos < earlier.endedAtNanos }

    /** 실패 메시지에 붙일 사람이 읽을 요약. SQL 은 우리가 쓴 스키마 질의뿐이라 본문이 실릴 자리가 없다. */
    fun describeWindows(): String =
        criticalWindows().joinToString(separator = "\n") { window ->
            "  ${window.thread}: ${window.startedAtNanos}..${window.endedAtNanos} " +
                "(${window.statementCount} statements)"
        }
}

/** 실행된 SQL 한 건. */
data class ExecutedStatement(
    val thread: String,
    val sql: String,
    val startedAtNanos: Long,
    val endedAtNanos: Long,
) {
    /**
     * 잠금 획득·해제 자체인가.
     *
     * 우리 가드의 `pg_advisory_lock`/`pg_advisory_unlock` 과 Flyway 가 이력 테이블에
     * 거는 `pg_try_advisory_lock` 을 함께 걷어낸다. 둘 다 **동기화 원시 연산**이지
     * 스키마를 읽거나 쓰는 일이 아니다.
     */
    val isSynchronization: Boolean get() = SYNCHRONIZATION_MARKER in sql

    private companion object {
        const val SYNCHRONIZATION_MARKER = "pg_advisory"
    }
}

/** 한 스레드가 임계 구간에 머문 구간. */
data class CriticalWindow(
    val thread: String,
    val startedAtNanos: Long,
    val endedAtNanos: Long,
    val statementCount: Int,
)

/**
 * [MigrationStatementTracer] 에 기록을 남기는 `DataSource`.
 *
 * ## 도달 범위 — "모두" 가 아니다
 *
 * Flyway 설정에 URL 대신 이것을 넘기면 **`getConnection()` 으로 나간 커넥션이 직접 만든**
 * `Statement`·`PreparedStatement` 의 `execute*` 가 기록된다. 가드가 직접 여는 커넥션과
 * Flyway 가 내부에서 여는 커넥션이 둘 다 그 경로를 지나므로 임계 구간 측정은 성립한다.
 *
 * 그 밖 두 자리는 지나지 않는다 (리뷰 K-C).
 *
 *   1. `Statement.getConnection()`·`DatabaseMetaData.getConnection()` 이 돌려주는 것은
 *      프록시가 아니라 **원본 커넥션**이다. 거기서 만든 문장은 추적 밖이다.
 *   2. `prepareCall` 의 `CallableStatement` 는 `PreparedStatement` 계약으로만 프록시된다.
 *      호출부가 `CallableStatement` 로 캐스팅하면 `ClassCastException` 이 난다.
 *
 * 현재 Flyway·가드 경로는 둘 다 쓰지 않아 측정에 구멍이 없다. 그래도 적어 두는 이유는
 * **"모두" 라고 쓰면 다음 사람이 그 밖을 확인하지 않기 때문**이다 — 이 하네스가 잡으려는
 * 바로 그 형태다.
 */
class TracingDataSource(
    private val database: DatabaseHandle,
    private val tracer: MigrationStatementTracer,
) : DataSource {
    override fun getConnection(): Connection = traceStatements(database.connect(), tracer)

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = getConnection()

    override fun getLogWriter(): PrintWriter? = null

    override fun setLogWriter(out: PrintWriter?) = Unit

    override fun setLoginTimeout(seconds: Int) = Unit

    override fun getLoginTimeout(): Int = 0

    override fun getParentLogger(): Logger = Logger.getLogger(TracingDataSource::class.java.name)

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("추적용 DataSource 는 래퍼가 아니다")

    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}

/**
 * 이 커넥션의 `createStatement`·`prepareStatement`·`prepareCall` 이 돌려주는 `Statement` 를
 * 추적판으로 바꾼다. **이 커넥션이 직접 만든 것에 한한다** — 도달 범위는 [TracingDataSource] 참고.
 */
private fun traceStatements(
    connection: Connection,
    tracer: MigrationStatementTracer,
): Connection =
    Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
    ) { _, method, args ->
        val result = callDelegate(connection, method, args)
        // prepareStatement/prepareCall 은 첫 인자가 SQL 이다. createStatement 는 인자에 SQL 이 없고
        // 실행 시점에 넘어오므로 여기서는 null 로 둔다.
        when (result) {
            is PreparedStatement -> traceExecutions(result, PreparedStatement::class.java, tracer, args.sqlArgument())
            is Statement -> traceExecutions(result, Statement::class.java, tracer, preparedSql = null)
            else -> result
        }
    } as Connection

/** `execute*` 호출의 SQL·시작·종료 시각을 기록한다. 실패해도 기록은 남긴다. */
private fun traceExecutions(
    statement: Statement,
    contract: Class<out Statement>,
    tracer: MigrationStatementTracer,
    preparedSql: String?,
): Any =
    Proxy.newProxyInstance(contract.classLoader, arrayOf(contract)) { _, method, args ->
        if (!method.name.startsWith("execute")) {
            return@newProxyInstance callDelegate(statement, method, args)
        }
        val sql = args.sqlArgument() ?: preparedSql ?: UNKNOWN_SQL
        val startedAtNanos = System.nanoTime()
        try {
            callDelegate(statement, method, args)
        } finally {
            tracer.record(sql, startedAtNanos, System.nanoTime())
        }
    }

private fun Array<out Any?>?.sqlArgument(): String? = this?.firstOrNull() as? String

/** 리플렉션 호출이 감싸는 예외를 벗겨 원래 예외를 그대로 올린다. */
private fun callDelegate(
    target: Any,
    method: Method,
    args: Array<out Any?>?,
): Any? =
    try {
        method.invoke(target, *(args ?: emptyArray()))
    } catch (invocationFailure: InvocationTargetException) {
        throw invocationFailure.targetException
    }

private const val UNKNOWN_SQL = "(sql unknown)"
