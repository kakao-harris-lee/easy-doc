package kr.easydoc.infrastructure.auth

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * 나가는 SQL 문의 **개수**를 세는 [DataSource] 껍데기.
 *
 * ## 왜 시간이 아니라 개수인가
 *
 * 소유권 은닉의 시간 축 게이트(`WorkspaceEndpointReachTest` 의 응답 시간 비)는 **큰 격차만**
 * 잡는 그물이다. 실측으로 확인했다 — 소유 조건을 SQL `WHERE` 에서 빼고 「행을 읽은 뒤
 * Kotlin 에서 소유자를 비교」하도록 바꾼 변이가 비 1.013~1.090 으로 **문턱을 전혀 건드리지
 * 않았다**(문턱 2.0 이든 1.5 든 초록). 인덱스 적중과 불발의 차이는 밀리초 단위 응답에서
 * 측정 잡음에 묻힌다.
 *
 * 그 변이가 실제로 바꾸는 것은 **DB 왕복의 구조**다. 그래서 여기서는 시간 대신 구조를
 * 센다 — 「소유 판정과 갱신이 한 문장인가」는 잡음이 없는 정수다.
 *
 * ## 세는 것
 *
 * `Connection` 에서 문장을 만드는 세 메서드다(`prepareStatement`·`createStatement`·
 * `prepareCall`). 실행 횟수가 아니라 **문장 생성 횟수**를 세는 이유는, JDBC 드라이버의
 * 배치·재사용 최적화가 실행 쪽 계측을 흔들기 때문이다.
 *
 * 반사 호출의 [InvocationTargetException] 은 **원인을 벗겨서** 다시 던진다. 그대로 두면
 * `DuplicateKeyException` 을 기대하는 다른 단언이 껍데기 예외를 보게 된다.
 */
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
