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

/** 문서·변환 저장 경로 조립 — **이 모듈이 소유한다.** */
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

    /** 마스킹 대응표 코덱 — **읽기 포트로만 노출한다.** */
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
