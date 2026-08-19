package kr.easydoc.infrastructure.db

import kr.easydoc.application.auth.TransactionRunner
import org.springframework.transaction.support.TransactionTemplate

/**
 * [TransactionRunner] 포트의 Spring 구현.
 *
 * ## 왜 `@Transactional` 이 아닌가
 *
 * 트랜잭션 경계는 유스케이스 계층이 연다(kotlin-spring-conventions §6.2 — 유스케이스
 * 하나 = 트랜잭션 하나). 그런데 `application` 모듈은 **Spring 을 의존하지 않으므로**
 * 거기에 `@Transactional` 을 붙일 수 없다. 포트를 두면 경계 선언은 유스케이스에 남고
 * 구현만 여기로 내려온다.
 *
 * `@Transactional` 프록시를 쓰지 않는 부수 효과 하나가 오히려 이득이다 — 같은 클래스
 * 안의 자기 호출에서 프록시가 벗겨져 트랜잭션이 조용히 사라지는 함정이 없다.
 *
 * ## 격리 수준을 명시하지 않는다 — 그리고 **READ COMMITTED 를 전제로 삼는 코드가 있다** (A-4)
 *
 * [TransactionTemplate] 기본값을 따르므로 실제 값은 DataSource/DB 기본
 * (PostgreSQL = **READ COMMITTED**)이다. `WorkspaceService.delete` 의 동시 삭제 정합성이
 * 그 수준의 동작 하나에 기대고 있다: 앞선 트랜잭션이 커밋한 뒤 `FOR UPDATE` 가 잠금을
 * 얻으면 **조건을 다시 평가해** 삭제된 행을 결과에서 빼 준다(EPQ). 그래서 둘째 요청이
 * 「남은 것이 하나뿐」을 보고 409 를 낸다.
 *
 * **누군가 REPEATABLE READ 이상으로 올리면 그 시나리오가 409 가 아니라 직렬화 실패
 * (SQLSTATE 40001) → 500 이 된다.** 전제가 어디에도 적혀 있지 않아 조용히 깨질 자리였다.
 * 격리 수준을 올리려면 `JdbcWorkspaceRepositoryTest` 의 「동시 삭제가 직렬화된다」를
 * 함께 봐야 하고, 그쪽은 실패를 **`ConflictException` 으로 못박아** 두었으므로 직렬화
 * 실패로 바뀌면 빨개진다.
 */
class SpringTransactionRunner(private val template: TransactionTemplate) : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T =
        // execute 의 반환 타입이 nullable 이라 non-null 블록 결과를 그대로 돌려주려면
        // 한 번 감싸야 한다. `!!` 를 쓰지 않는다(§7).
        checkNotNull(template.execute { Holder(block()) }) { "트랜잭션 실행 결과가 없습니다" }.value

    /** `TransactionCallback` 이 `null` 을 정상값으로 다루므로 결과를 감싸 구분한다. */
    private class Holder<T>(val value: T)
}
