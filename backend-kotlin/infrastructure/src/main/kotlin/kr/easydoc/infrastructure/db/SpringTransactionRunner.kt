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
 */
class SpringTransactionRunner(private val template: TransactionTemplate) : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T =
        // execute 의 반환 타입이 nullable 이라 non-null 블록 결과를 그대로 돌려주려면
        // 한 번 감싸야 한다. `!!` 를 쓰지 않는다(§7).
        checkNotNull(template.execute { Holder(block()) }) { "트랜잭션 실행 결과가 없습니다" }.value

    /** `TransactionCallback` 이 `null` 을 정상값으로 다루므로 결과를 감싸 구분한다. */
    private class Holder<T>(val value: T)
}
