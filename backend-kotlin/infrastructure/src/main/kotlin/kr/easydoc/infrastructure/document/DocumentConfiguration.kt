package kr.easydoc.infrastructure.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionQueue
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.WorkspaceLookup
import kr.easydoc.infrastructure.crypto.MIGRATE_PROFILE
import kr.easydoc.infrastructure.queue.JdbcConversionQueue
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient

/**
 * 문서·변환 저장 경로 조립 — **이 모듈이 소유한다.**
 *
 * `AuthConfiguration`·`CryptoConfiguration`·`IngestConfiguration` 과 같은 자리이고 이유도
 * 같다: 구현 클래스를 볼 수 있는 모듈이 `infrastructure` 하나뿐이다. `api`·`worker` 는
 * `runtimeOnly(project(":infrastructure"))` 라 [JdbcDocumentRepository] 타입도
 * `JdbcClient` 도 컴파일 시점에 보지 못한다.
 *
 * ## `WorkspaceLookup` 빈이 따로 있는 이유
 *
 * `AuthConfiguration.workspaceRepository` 의 **선언 타입**이 `WorkspaceRepository` 라, 같은
 * 인스턴스여도 Spring 은 그것을 [WorkspaceLookup] 으로 주입하지 못한다(빈 정의의 타입은
 * `@Bean` 메서드의 반환 타입이다). 그렇다고 `JdbcWorkspaceRepository` 가 두 포트를 함께
 * 구현하게 하면 **같은 구상 타입의 빈이 둘**이 되어 두 포트 어느 쪽 주입도 모호해진다
 * (실측: `api`·`worker` 기동 테스트 전건 빨강 — 경위는 [JdbcWorkspaceLookup] KDoc).
 *
 * 그래서 **포트 하나당 구상 클래스 하나**로 간다. 문서 경로가 가질 수 있는 권한이 읽기
 * 둘로 좁혀지는 것은 덤이 아니라 목적이다 — 원본
 * `app/services/documents.py::WorkspaceLookup` 이 같은 판단을 적었다.
 *
 * ## `migrate` 프로필에서 조립되지 않는다
 *
 * `@Profile("!migrate")` 다. 이유는 면제가 아니라 **의존성**이다 — 이 설정이 만드는
 * [DocumentService]·[EnvelopeRotation] 이 [ContentCipher] 를 요구하는데, 그 빈은
 * `CryptoConfiguration` 이 같은 조건으로 빼 두었다(게이트 26 조치 2 — 스키마만 옮기는 잡이
 * 본문 암호화 키를 쥐지 않는다). 조건을 맞추지 않으면 `migrate` 실행이 "ContentCipher 빈이
 * 없다"로 기동에 실패한다.
 *
 * 부정 목록(`!migrate`)이지 허용 목록이 아닌 것은 `CryptoConfiguration` 과 같은 의도다 —
 * 새 프로필이 생기면 **저장 경로를 갖는 쪽**이 기본이어야 한다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!$MIGRATE_PROFILE")
class DocumentConfiguration {
    @Bean
    fun documentRepository(jdbcClient: JdbcClient): DocumentRepository = JdbcDocumentRepository(jdbcClient)

    @Bean
    fun conversionRepository(jdbcClient: JdbcClient): ConversionRepository = JdbcConversionRepository(jdbcClient)

    @Bean
    fun conversionQueue(jdbcClient: JdbcClient): ConversionQueue = JdbcConversionQueue(jdbcClient)

    /** 사유는 클래스 KDoc 의 「`WorkspaceLookup` 빈이 따로 있는 이유」. */
    @Bean
    fun workspaceLookup(jdbcClient: JdbcClient): WorkspaceLookup = JdbcWorkspaceLookup(jdbcClient)

    /**
     * 마스킹 대응표 코덱.
     *
     * 저장 경로가 아직 이 코덱을 부르지 않는다(쓰는 쪽은 Phase 5 워커, 읽는 쪽은 변환 조회
     * 커밋이다). 그래도 빈으로 올리는 이유는, 형식을 정한 커밋과 그것을 쓰는 커밋 사이에
     * **조립이 되는지 아무도 확인하지 않는 구간**을 만들지 않기 위해서다.
     */
    @Bean
    fun maskedItemCodec(): MaskedItemCodec = MaskedItemCodec()

    /** 업로드가 한 트랜잭션에서 쓰는 세 저장소. 묶는 사유는 [DocumentStorage] KDoc. */
    @Bean
    fun documentStorage(
        documents: DocumentRepository,
        conversions: ConversionRepository,
        queue: ConversionQueue,
    ): DocumentStorage = DocumentStorage(documents = documents, conversions = conversions, queue = queue)

    @Bean
    fun documentService(
        storage: DocumentStorage,
        workspaces: WorkspaceLookup,
        cipher: ContentCipher,
        extractor: DocumentTextExtractor,
        transactionRunner: TransactionRunner,
    ): DocumentService =
        DocumentService(
            storage = storage,
            workspaces = workspaces,
            cipher = cipher,
            extractor = extractor,
            transaction = transactionRunner,
        )

    /**
     * 키 회전 유스케이스.
     *
     * **호출자가 아직 없다**(운영 CLI·worker 스케줄·마이그레이션 중 무엇인지는 계획 §9 질문
     * ⑦ 의 열린 판정이다). 빈으로 올려 두는 이유는 [maskedItemCodec] 과 같다 — 조립이
     * 실제로 되는지가 판정 시점까지 미검증으로 남지 않게 한다.
     */
    @Bean
    fun envelopeRotation(
        documents: DocumentRepository,
        conversions: ConversionRepository,
        cipher: ContentCipher,
        transactionRunner: TransactionRunner,
    ): EnvelopeRotation =
        EnvelopeRotation(
            documents = documents,
            conversions = conversions,
            cipher = cipher,
            transaction = transactionRunner,
        )
}
