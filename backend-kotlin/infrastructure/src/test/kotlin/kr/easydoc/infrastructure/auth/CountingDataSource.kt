package kr.easydoc.infrastructure.auth

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** 나가는 SQL 문의 개수를 세는 [DataSource] 껍데기. */
class CountingDataSource(private val delegate: DataSource) : DataSource by delegate {
    private val statements = AtomicInteger()

    override fun getConnection(): Connection = counting(delegate.connection)

    override fun getConnection(
        username: String?,
        password: String?,
    ): Connection = counting(delegate.getConnection(username, password))

    /** [block] 이 도는 동안 만들어진 SQL 문 수. 호출마다 0 에서 시작한다. */
    fun countStatements(block: () -> Unit): Int {
        statements.set(0)
        block()
        return statements.get()
    }

    private fun counting(connection: Connection): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (method.name in STATEMENT_FACTORIES) {
                statements.incrementAndGet()
            }
            try {
                method.invoke(connection, *(args ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as Connection

    private companion object {
        val STATEMENT_FACTORIES = setOf("prepareStatement", "createStatement", "prepareCall")
    }
}
