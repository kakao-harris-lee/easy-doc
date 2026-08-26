package kr.easydoc.infrastructure.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionFeedbackRepository
import kr.easydoc.application.document.ConversionFeedbackService
import kr.easydoc.application.document.ConversionQueryService
import kr.easydoc.application.document.ConversionQueue
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.ConversionReviewService
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.MaskedItemReader
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
 * `TooManyFunctions` 를 억제한다: 조립 지점의 함수 수는 이 클래스의 복잡도가 아니라
 * **협력자의 수**다. 규칙을 지키려고 조립을 두 클래스로 가르면 「어느 파일이 무엇을
 * 조립하는가」를 사람이 기억해야 하고, 그 순간 조립 지점이 하나라는 성질이 사라진다
 * (프로젝트 `CLAUDE.md` 「Spring `@Configuration` 은 composition root 다」). 억제는 이
 * 클래스 하나에 걸리고 도메인·유스케이스 코드로 번지지 않는다.
 */
@Suppress("TooManyFunctions")
@Configuration(proxyBeanMethods = false)
@Profile("!$MIGRATE_PROFILE")
class DocumentConfiguration {
    @Bean
    fun documentRepository(jdbcClient: JdbcClient): DocumentRepository = JdbcDocumentRepository(jdbcClient)

    @Bean
    fun conversionRepository(jdbcClient: JdbcClient): ConversionRepository = JdbcConversionRepository(jdbcClient)

    @Bean
    fun conversionQueue(jdbcClient: JdbcClient): JdbcConversionQueue = JdbcConversionQueue(jdbcClient)

    /** 사유는 클래스 KDoc 의 「`WorkspaceLookup` 빈이 따로 있는 이유」. */
    @Bean
    fun workspaceLookup(jdbcClient: JdbcClient): WorkspaceLookup = JdbcWorkspaceLookup(jdbcClient)

    /** 마스킹 대응표 코덱. 읽기·쓰기 포트는 이 한 빈이 모두 만족한다. */
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

    /** 변환 조회 유스케이스. */
    @Bean
    fun conversionQueryService(
        conversions: ConversionRepository,
        cipher: ContentCipher,
        maskedItems: MaskedItemReader,
        transactionRunner: TransactionRunner,
    ): ConversionQueryService =
        ConversionQueryService(
            conversions = conversions,
            cipher = cipher,
            maskedItems = maskedItems,
            transaction = transactionRunner,
        )

    /** 검수 저장 유스케이스. 응답 조립은 조회 쪽을 그대로 쓴다. */
    @Bean
    fun conversionReviewService(
        conversions: ConversionRepository,
        cipher: ContentCipher,
        query: ConversionQueryService,
        transactionRunner: TransactionRunner,
    ): ConversionReviewService =
        ConversionReviewService(
            conversions = conversions,
            cipher = cipher,
            query = query,
            transaction = transactionRunner,
        )

    /** 파일럿 피드백 저장소. 문서·변환과 **수명이 분리된** 표라 저장소도 따로 선다. */
    @Bean
    fun conversionFeedbackRepository(jdbcClient: JdbcClient): ConversionFeedbackRepository =
        JdbcConversionFeedbackRepository(jdbcClient)

    /** 파일럿 피드백 저장 유스케이스. 소유·상태 판정은 조회 쪽을 그대로 쓴다. */
    @Bean
    fun conversionFeedbackService(
        feedback: ConversionFeedbackRepository,
        cipher: ContentCipher,
        query: ConversionQueryService,
        transactionRunner: TransactionRunner,
    ): ConversionFeedbackService =
        ConversionFeedbackService(
            feedback = feedback,
            cipher = cipher,
            query = query,
            transaction = transactionRunner,
        )

    /** 키 회전 유스케이스. */
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
