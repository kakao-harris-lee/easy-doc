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

/** Flyway 기동 경로가 실제로 실행한 SQL 을 스레드·시각과 함께 기록하는 탐침. */
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

    /** 스레드마다 임계 구간에 머문 구간 하나. 시작 시각 오름차순이다. */
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

    /** 겹치는 구간 쌍. 하나라도 있으면 두 기동이 동시에 판정·기록에 들어갔다는 뜻이다. */
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
    /** 잠금 획득·해제 자체인가. */
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

/** [MigrationStatementTracer] 에 기록을 남기는 `DataSource`. */
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
 * 추적판으로 바꾼다. 이 커넥션이 직접 만든 것에 한한다 — 도달 범위는 [TracingDataSource] 참고.
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
