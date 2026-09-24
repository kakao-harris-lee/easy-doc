package kr.easydoc.infrastructure.document

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * [DocumentJobLocks] 만 조립하는 작은 설정이다. 문서 여러 건을 한 문장으로 지우는 경로가 모두
 * 쓴다 — worker 의 보존 만료 파기(`JdbcExpiredDocumentPurge`)와 탈퇴
 * (`JdbcAccountDeletionRepository`)다.
 *
 * 그래서 문서 조립의 본진(`DocumentConfiguration`)에 둘 수 없다 — 그쪽은 `!migrate` 라
 * migrate 문맥에서 빠지는데, 탈퇴 저장소를 만드는 `AuthConfiguration` 은 프로필을 가리지 않아
 * 그 문맥에서도 이 협력자를 찾는다. worker 전용 설정도 같은 이유로 안 된다.
 */
@Configuration(proxyBeanMethods = false)
class DocumentJobLockConfiguration {
    @Bean
    fun documentJobLocks(jdbcClient: JdbcClient): DocumentJobLocks = DocumentJobLocks(jdbcClient)
}
