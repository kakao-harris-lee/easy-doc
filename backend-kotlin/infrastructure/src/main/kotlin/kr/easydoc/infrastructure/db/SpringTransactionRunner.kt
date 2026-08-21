package kr.easydoc.infrastructure.db

import kr.easydoc.application.auth.TransactionRunner
import org.springframework.transaction.support.TransactionTemplate

// 동시 삭제 직렬화 검증: `JdbcWorkspaceRepositoryTest`.

/** [TransactionRunner] 포트의 Spring 구현. */
class SpringTransactionRunner(private val template: TransactionTemplate) : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T =
        // execute 의 반환 타입이 nullable 이라 non-null 블록 결과를 그대로 돌려주려면
        // 한 번 감싸야 한다. `!!` 를 쓰지 않는다(§7).
        checkNotNull(template.execute { Holder(block()) }) { "트랜잭션 실행 결과가 없습니다" }.value

    /** `TransactionCallback` 이 `null` 을 정상값으로 다루므로 결과를 감싸 구분한다. */
    private class Holder<T>(val value: T)
}
