package kr.easydoc.infrastructure.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.conversion.ConvertDocumentUseCase
import kr.easydoc.application.conversion.ReconvertUnitService
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionFeedbackRepository
import kr.easydoc.application.document.ConversionFeedbackService
import kr.easydoc.application.document.ConversionQueryService
import kr.easydoc.application.document.ConversionQueue
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.ConversionReviewService
import kr.easydoc.application.document.DocumentOriginalRepository
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentSourceService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.EnvelopeRotation
import kr.easydoc.application.document.MaskedItemReader
import kr.easydoc.application.document.OriginalReflection
import kr.easydoc.application.document.OriginalStructureReflector
import kr.easydoc.application.document.SealedStores
import kr.easydoc.application.document.StoredOriginalReader
import kr.easydoc.application.document.WorkspaceLookup
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmProvider
import kr.easydoc.infrastructure.crypto.MIGRATE_PROFILE
import kr.easydoc.infrastructure.export.PackagedOriginalReflector
import kr.easydoc.infrastructure.llm.LlmProperties
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

    /** 업로드 원본 저장소. `documents` 와 표가 갈린 사유는 `V3__document_originals.sql`. */
    @Bean
    fun documentOriginalRepository(jdbcClient: JdbcClient): DocumentOriginalRepository =
        JdbcDocumentOriginalRepository(jdbcClient)

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

    /** 업로드가 한 트랜잭션에서 쓰는 네 저장소. 묶는 사유는 [DocumentStorage] KDoc. */
    @Bean
    fun documentStorage(
        documents: DocumentRepository,
        originals: DocumentOriginalRepository,
        conversions: ConversionRepository,
        queue: ConversionQueue,
    ): DocumentStorage =
        DocumentStorage(
            documents = documents,
            originals = originals,
            conversions = conversions,
            queue = queue,
        )

    @Suppress("LongParameterList")
    @Bean
    fun documentService(
        storage: DocumentStorage,
        workspaces: WorkspaceLookup,
        cipher: ContentCipher,
        extractor: DocumentTextExtractor,
        transactionRunner: TransactionRunner,
        users: UserRepository,
    ): DocumentService =
        DocumentService(
            storage = storage,
            workspaces = workspaces,
            cipher = cipher,
            extractor = extractor,
            transaction = transactionRunner,
            users = users,
        )

    /**
     * 원문 조회 유스케이스 — 검수 화면의 왼쪽 절반이 읽는 자리.
     *
     * [documentService] 와 갈라 세운 사유는 `DocumentSourceService` KDoc 이다: 저쪽은 업로드
     * 한 번이 쓰는 네 저장소를 묶고, 이쪽의 협력자는 저장소 하나와 암호 하나뿐이다.
     */
    @Bean
    fun documentSourceService(
        documents: DocumentRepository,
        cipher: ContentCipher,
    ): DocumentSourceService = DocumentSourceService(documents = documents, cipher = cipher)

    /**
     * 원본 구조 반영. 조회의 서식 유지 판정과 내보내기가 **같은 이 하나**를 쓴다 —
     * 둘로 두면 「미리 말한 것」과 「실제로 한 것」이 갈린다.
     */
    @Bean
    fun originalStructureReflector(): OriginalStructureReflector = PackagedOriginalReflector()

    /**
     * 원본을 **여는 쪽과 반영하는 쪽**을 한 묶음으로 세운다.
     *
     * 내보내기 조립이 아니라 여기 있는 것은 **조회가 이것을 쓰기 때문이다** — 서식 유지 판정은
     * 내려받기 전에 나가는 값이라 저장 조립만으로 컨텍스트가 서야 한다(`DocumentStorageContextTest`).
     */
    @Bean
    fun originalReflection(
        originals: DocumentOriginalRepository,
        cipher: ContentCipher,
        reflector: OriginalStructureReflector,
    ): OriginalReflection =
        OriginalReflection(
            originals = StoredOriginalReader(originals = originals, cipher = cipher),
            reflector = reflector,
        )

    /** 변환 조회 유스케이스. */
    @Suppress("LongParameterList")
    @Bean
    fun conversionQueryService(
        conversions: ConversionRepository,
        cipher: ContentCipher,
        maskedItems: MaskedItemReader,
        original: OriginalReflection,
        documents: DocumentRepository,
        transactionRunner: TransactionRunner,
    ): ConversionQueryService =
        ConversionQueryService(
            conversions = conversions,
            cipher = cipher,
            maskedItems = maskedItems,
            original = original,
            documents = documents,
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

    /**
     * 재변환 전용 `ConvertDocumentUseCase` — **worker 가 아닌 프로필에서만** 조립한다
     * (`@Profile("!worker")`, 클래스 레벨 `!$MIGRATE_PROFILE` 과 함께 걸려 사실상 api/local/test
     * 에서만 선다). worker 프로필은 `ConversionWorkerConfiguration.convertDocumentUseCase` 가
     * 이미 같은 타입의 빈을 조립하므로, 여기서 조건 없이 만들면 두 정의가 겹친다.
     *
     * **사전 컨텍스트를 주입하지 않는다**(기본값 `NoDictionaryContext`) — 재변환은 문서
     * 전체가 아니라 단위 하나만 다시 변환하고, 그 규모에 1.5MB 사전 색인을 적재할 이유가
     * 없다(`ConversionWorkerConfiguration.dictionaryContextSource` 와 같은 판단).
     */
    @Bean
    @Profile("!worker")
    fun convertDocumentUseCase(
        provider: LlmProvider,
        properties: LlmProperties,
    ): ConvertDocumentUseCase =
        ConvertDocumentUseCase(provider, defaultOptions = LlmOptions(maxTokens = properties.validatedMaxOutputTokens()))

    /** 재변환 유스케이스 — `easydoc.reconversion.call-budget` 은 [ReconversionProperties] 가 문다. */
    @Suppress("LongParameterList")
    @Bean
    fun reconvertUnitService(
        conversions: ConversionRepository,
        documents: DocumentRepository,
        cipher: ContentCipher,
        convert: ConvertDocumentUseCase,
        transactionRunner: TransactionRunner,
        properties: ReconversionProperties,
    ): ReconvertUnitService =
        ReconvertUnitService(
            conversions = conversions,
            documents = documents,
            cipher = cipher,
            convert = convert,
            transaction = transactionRunner,
            callBudget = properties.callBudget,
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
        feedbackProperties: FeedbackProperties,
    ): ConversionFeedbackService =
        ConversionFeedbackService(
            feedback = feedback,
            cipher = cipher,
            query = query,
            transaction = transactionRunner,
            editDistanceBudget = feedbackProperties.editDistanceBudget(),
        )

    /** 봉인된 열이 사는 저장소 전부. 묶는 사유는 [SealedStores] KDoc. */
    @Bean
    fun sealedStores(
        documents: DocumentRepository,
        originals: DocumentOriginalRepository,
        conversions: ConversionRepository,
        feedback: ConversionFeedbackRepository,
    ): SealedStores =
        SealedStores(
            documents = documents,
            originals = originals,
            conversions = conversions,
            feedback = feedback,
        )

    /** 키 회전 유스케이스. 봉인된 열이 사는 저장소를 [SealedStores] 로 **전부** 받는다. */
    @Bean
    fun envelopeRotation(
        stores: SealedStores,
        cipher: ContentCipher,
        transactionRunner: TransactionRunner,
    ): EnvelopeRotation = EnvelopeRotation(stores = stores, cipher = cipher, transaction = transactionRunner)
}
