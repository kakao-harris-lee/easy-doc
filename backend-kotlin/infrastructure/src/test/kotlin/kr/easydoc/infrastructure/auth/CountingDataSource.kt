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
 *
 * ## 세지 **않는** 것 — 그리고 왜 그것이 오늘 문제가 되지 않는가 (게이트 23 codex C-3)
 *
 * 돌려준 `Statement` 를 다시 감싸지 않으므로 `executeQuery`·`executeUpdate` 는 계측 대상이
 * 아니다. 즉 **`createStatement()` 로 얻은 문장 하나에 SQL 둘을 태우면** 이 계수기는 1 을
 * 본다. 그 우회는 `JdbcClient` 를 버리고 raw JDBC 로 내려가야 성립한다 — `JdbcClient` 는
 * SQL 문자열 하나당 `PreparedStatement` 하나를 만들고 한 `PreparedStatement` 에 다른 SQL 을
 * 태울 수 없기 때문이다. **저장소가 `JdbcClient` 를 벗어나는 순간 이 계수기의 전제가
 * 깨진다** — 그때 이 KDoc 을 함께 고쳐야 한다.
 *
 * **그 전제는 이제 산문이 아니라 장치다**(원장 K-2, 문서 저장소 커밋에서 닫았다):
 * [kr.easydoc.infrastructure.db.StatementCountingPremiseTest] 가 `application` 포트를
 * 구현한 `infrastructure` 클래스를 **종류로** 훑어, 그중 누구도 raw JDBC 손잡이
 * (`DataSource`·`Connection`·`JdbcTemplate` 계열)를 들지 않음을 상시로 확인한다.
 * 그 파일의 KDoc 에 **이 장치가 막지 못하는 것**도 함께 적혀 있다.
 *
 * ## 계측이 들어가는 자리 — **서비스 경계다** (게이트 23 F-4)
 *
 * 종전에는 `JdbcWorkspaceRepository.rename` 한 메서드를 감쌌다. 그러면 「소유 조건이 SQL 을
 * 떠났는가」라는 선언된 주제가 **한 층 위에서는 검사되지 않는다** — 소유 판정을
 * `WorkspaceService` 로 올린 변이(서비스가 `listOwned()` 로 먼저 확인하고 저장소는 그대로)가
 * 구조 축 11/11 · 시간 축 22/22 전부 초록으로 빠져나갔다(migration-reviewer F-4 실증).
 *
 * 그래서 계측 진입점을 **유스케이스 한 번 = 요청 한 번**으로 올리고, 「셋이 같다」가 아니라
 * **문장 수 자체를 못박는다.** 어느 층에 조회가 하나 늘든 그 정수가 움직인다.
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
