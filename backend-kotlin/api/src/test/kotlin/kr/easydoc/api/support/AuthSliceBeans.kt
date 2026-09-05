package kr.easydoc.api.support

import kr.easydoc.application.auth.AccessTokens
import kr.easydoc.application.auth.AuthService
import kr.easydoc.application.auth.ConsumedOAuthState
import kr.easydoc.application.auth.EmailVerificationService
import kr.easydoc.application.auth.IssuedAccessToken
import kr.easydoc.application.auth.OAuthChallenge
import kr.easydoc.application.auth.OAuthStateStore
import kr.easydoc.application.auth.PasswordHasher
import kr.easydoc.application.auth.SocialIdentity
import kr.easydoc.application.auth.SocialLoginProvider
import kr.easydoc.application.auth.SocialLoginProviderId
import kr.easydoc.application.auth.SocialLoginRepositories
import kr.easydoc.application.auth.SocialLoginService
import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.auth.UserIdentity
import kr.easydoc.application.auth.UserIdentityRepository
import kr.easydoc.application.auth.UserRepository
import kr.easydoc.application.auth.VerificationCodeStore
import kr.easydoc.application.auth.WorkspaceDeletionState
import kr.easydoc.application.auth.WorkspaceRepository
import kr.easydoc.application.conversion.ConvertDocumentUseCase
import kr.easydoc.application.conversion.ReconvertUnitService
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.dictionary.DictionaryAttribution
import kr.easydoc.application.dictionary.DictionaryAttributionProvider
import kr.easydoc.application.dictionary.LookupRateLimiter
import kr.easydoc.application.dictionary.TermCandidateSource
import kr.easydoc.application.dictionary.TermLookupService
import kr.easydoc.application.document.ConversionExportService
import kr.easydoc.application.document.ConversionFeedbackService
import kr.easydoc.application.document.ConversionQueryService
import kr.easydoc.application.document.ConversionReviewService
import kr.easydoc.application.document.DocumentExporter
import kr.easydoc.application.document.DocumentService
import kr.easydoc.application.document.DocumentSourceService
import kr.easydoc.application.document.DocumentStorage
import kr.easydoc.application.document.DocumentTextExtractor
import kr.easydoc.application.document.ExportRendering
import kr.easydoc.application.document.MaskedItemReader
import kr.easydoc.application.document.OriginalReflection
import kr.easydoc.application.document.StoredOriginalReader
import kr.easydoc.application.document.WorkspaceLookup
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.workspace.DUPLICATE_WORKSPACE_NAME_MESSAGE
import kr.easydoc.application.workspace.WorkspaceService
import kr.easydoc.core.dictionary.DictionaryExample
import kr.easydoc.core.dictionary.ReplaceStrategy
import kr.easydoc.core.dictionary.RiskLevel
import kr.easydoc.core.dictionary.TermCandidate
import kr.easydoc.core.dictionary.TermMatchKind
import kr.easydoc.core.dictionary.TermQuery
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.easyread.exportFileOf
import kr.easydoc.core.easyread.renderTxt
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.EmailAlreadyRegisteredException
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidCredentialsException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.RateLimitedException
import kr.easydoc.core.text.EditDistanceBudget
import kr.easydoc.core.user.PasswordHash
import kr.easydoc.core.user.StoredUser
import kr.easydoc.core.user.User
import kr.easydoc.core.workspace.DEFAULT_WORKSPACE_NAME
import kr.easydoc.core.workspace.Workspace
import kr.easydoc.core.workspace.WorkspaceListing
import kr.easydoc.infrastructure.dictionary.InMemorySlidingWindowLookupRateLimiter
import kr.easydoc.infrastructure.dictionary.NoTermCandidateSource
import kr.easydoc.infrastructure.document.FeedbackProperties
import kr.easydoc.infrastructure.mail.FakeMailSender
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** `@WebMvcTest` 슬라이스가 쓰는 인증 배선. */
@TestConfiguration(proxyBeanMethods = false)
class AuthSliceBeans {
    @Bean
    fun inMemoryUsers(): InMemoryUserRepository = InMemoryUserRepository()

    @Bean
    fun inMemoryWorkspaces(): InMemoryWorkspaceRepository = InMemoryWorkspaceRepository()

    @Bean
    fun stubPasswordHasher(): StubPasswordHasher = StubPasswordHasher()

    @Bean
    fun stubAccessTokens(): StubAccessTokens = StubAccessTokens()

    @Bean
    fun directTransactionRunner(): TransactionRunner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    /** 조립 지점의 매개변수 수는 협력자의 수다 — 제품 조립(`AuthConfiguration`)과 같은 근거로 억제한다. */
    @Suppress("LongParameterList")
    @Bean
    fun authService(
        users: InMemoryUserRepository,
        workspaces: InMemoryWorkspaceRepository,
        hasher: StubPasswordHasher,
        tokens: StubAccessTokens,
        transaction: TransactionRunner,
        emailVerification: EmailVerificationService,
    ): AuthService = AuthService(users, workspaces, hasher, tokens, transaction, emailVerification)

    @Bean
    fun fakeMailSender(): FakeMailSender = FakeMailSender()

    @Bean
    fun inMemoryVerificationCodes(): InMemoryVerificationCodeStore = InMemoryVerificationCodeStore()

    /** 유스케이스도 실물이다 — 제품 조립(`AuthConfiguration.emailVerificationService`)과 같은 모양이다. */
    @Bean
    fun emailVerificationService(
        users: InMemoryUserRepository,
        codes: InMemoryVerificationCodeStore,
        mail: FakeMailSender,
    ): EmailVerificationService =
        EmailVerificationService(
            users = users,
            codes = codes,
            mail = mail,
            codeTtl = Duration.ofMinutes(10),
            resendCooldown = Duration.ofSeconds(60),
            maxAttempts = 5,
        )

    @Bean
    fun inMemoryUserIdentities(): InMemoryUserIdentityRepository = InMemoryUserIdentityRepository()

    @Bean
    fun inMemoryOAuthStates(): InMemoryOAuthStateStore = InMemoryOAuthStateStore()

    @Bean
    fun fakeGoogleProvider(): FakeGoogleSocialLoginProvider = FakeGoogleSocialLoginProvider()

    /** 카카오 대역 — backlog §1.4, 계약 2.13.0. [FakeGoogleSocialLoginProvider] 와 같은 방식. */
    @Bean
    fun fakeKakaoProvider(): FakeKakaoSocialLoginProvider = FakeKakaoSocialLoginProvider()

    /** `SocialLoginService` 생성자 매개변수 상한을 지키려고 저장소 셋을 묶는다(그 클래스 KDoc). */
    @Bean
    fun socialLoginRepositories(
        users: InMemoryUserRepository,
        identities: InMemoryUserIdentityRepository,
        workspaces: InMemoryWorkspaceRepository,
    ): SocialLoginRepositories = SocialLoginRepositories(users, identities, workspaces)

    /**
     * `SocialLoginService` 는 실물이다 — 계약이 정한 판정 순서(state → 신원 → 이메일 규칙)를
     * 슬라이스가 실제로 밟아야 한다. google·kakao 둘을 등록한다(제품 조립과 같은 모양,
     * 카카오는 계약 2.13.0).
     */
    @Suppress("LongParameterList")
    @Bean
    fun socialLoginService(
        google: FakeGoogleSocialLoginProvider,
        kakao: FakeKakaoSocialLoginProvider,
        states: InMemoryOAuthStateStore,
        repositories: SocialLoginRepositories,
        tokens: StubAccessTokens,
        transaction: TransactionRunner,
    ): SocialLoginService =
        SocialLoginService(
            providers =
                mapOf(
                    SocialLoginProviderId.GOOGLE to google,
                    SocialLoginProviderId.KAKAO to kakao,
                ),
            states = states,
            repositories = repositories,
            accessTokens = tokens,
            transaction = transaction,
            stateTtl = java.time.Duration.ofMinutes(10),
        )

    /**
     * `@WebMvcTest` 는 컨트롤러를 전부 슬라이스에 넣는다. `WorkspaceController` 가
     * 생긴 순간부터 이 빈이 없으면 `/auth` 만 겨누는 테스트도 컨텍스트 조립에서 멈춘다.
     */
    @Bean
    fun workspaceService(
        workspaces: InMemoryWorkspaceRepository,
        transaction: TransactionRunner,
    ): WorkspaceService = WorkspaceService(workspaces, transaction)

    @Bean
    fun inMemoryDocuments(): InMemoryDocumentRepository = InMemoryDocumentRepository()

    @Bean
    fun inMemoryConversions(
        documents: InMemoryDocumentRepository,
        originals: InMemoryDocumentOriginalRepository,
    ): InMemoryConversionRepository = InMemoryConversionRepository(documents, originals)

    @Bean
    fun recordingQueue(): RecordingConversionQueue = RecordingConversionQueue()

    @Bean
    fun inMemoryWorkspaceLookup(workspaces: InMemoryWorkspaceRepository): WorkspaceLookup =
        InMemoryWorkspaceLookup(workspaces)

    @Bean
    fun stubContentCipher(): ContentCipher = StubContentCipher()

    @Bean
    fun stubTextExtractor(): DocumentTextExtractor = StubDocumentTextExtractor()

    /** 마스킹 대응표 읽기 대역. 저장 형식을 흉내 내지 않는다 — 사유는 그 클래스 KDoc. */
    @Bean
    fun stubMaskedItemReader(): MaskedItemReader = StubMaskedItemReader()

    /**
     * 업로드가 한 트랜잭션에서 쓰는 네 저장소를 제품 조립과 같은 모양으로 묶는다
     * (`DocumentConfiguration.documentStorage`). 셋을 유스케이스에 따로 넘기면 그중
     * 하나만 다른 경계에 두는 배선이 타입으로 막히지 않는다.
     */
    @Bean
    fun documentOriginalRepository(documents: InMemoryDocumentRepository): InMemoryDocumentOriginalRepository =
        InMemoryDocumentOriginalRepository(documents)

    @Bean
    fun documentStorage(
        documents: InMemoryDocumentRepository,
        originals: InMemoryDocumentOriginalRepository,
        conversions: InMemoryConversionRepository,
        queue: RecordingConversionQueue,
    ): DocumentStorage =
        DocumentStorage(
            documents = documents,
            originals = originals,
            conversions = conversions,
            queue = queue,
        )

    /**
     * 유스케이스는 실물이다 — 계약이 정한 검사 순서를 슬라이스가 실제로 밟아야 한다.
     * 매개변수 수는 협력자의 수다 — 제품 조립(`DocumentConfiguration`)과 같은 근거로 억제한다.
     */
    @Suppress("LongParameterList")
    @Bean
    fun documentService(
        storage: DocumentStorage,
        workspaceLookup: WorkspaceLookup,
        cipher: ContentCipher,
        extractor: DocumentTextExtractor,
        transaction: TransactionRunner,
        users: InMemoryUserRepository,
    ): DocumentService =
        DocumentService(
            storage = storage,
            workspaces = workspaceLookup,
            cipher = cipher,
            extractor = extractor,
            transaction = transaction,
            users = users,
        )

    /** 원문 조회 유스케이스도 실물이다. 협력자는 저장소 하나와 암호 하나뿐이다. */
    @Bean
    fun documentSourceService(
        documents: InMemoryDocumentRepository,
        cipher: ContentCipher,
    ): DocumentSourceService = DocumentSourceService(documents = documents, cipher = cipher)

    /** 조회 유스케이스도 실물이다 — 제품 조립과 같은 모양으로 나눈다. */
    @Suppress("LongParameterList")
    @Bean
    fun conversionQueryService(
        conversions: InMemoryConversionRepository,
        cipher: ContentCipher,
        maskedItems: MaskedItemReader,
        original: OriginalReflection,
        documents: InMemoryDocumentRepository,
        transaction: TransactionRunner,
    ): ConversionQueryService =
        ConversionQueryService(
            conversions = conversions,
            cipher = cipher,
            maskedItems = maskedItems,
            original = original,
            documents = documents,
            transaction = transaction,
        )

    /** 원본을 여는 쪽과 반영하는 쪽 한 묶음. 제품 조립과 같은 모양이다. */
    @Bean
    fun originalReflection(
        originals: InMemoryDocumentOriginalRepository,
        cipher: ContentCipher,
        reflector: SliceOriginalReflector,
    ): OriginalReflection = OriginalReflection(StoredOriginalReader(originals, cipher), reflector)

    /** 슬라이스의 원본 반영 대역. 케이스가 갈래를 고를 수 있게 **구체 타입으로** 노출한다. */
    @Bean
    fun sliceOriginalReflector(): SliceOriginalReflector = SliceOriginalReflector()

    /** 검수 저장 유스케이스도 실물이다. 응답 조립은 조회 유스케이스를 그대로 쓴다. */
    @Bean
    fun conversionReviewService(
        conversions: InMemoryConversionRepository,
        cipher: ContentCipher,
        query: ConversionQueryService,
        transaction: TransactionRunner,
    ): ConversionReviewService =
        ConversionReviewService(
            conversions = conversions,
            cipher = cipher,
            query = query,
            transaction = transaction,
        )

    /** 재변환 슬라이스의 LLM 대역 — 테스트가 [ControllableLlmProvider.willReturn] 으로 응답을 정한다. */
    @Bean
    fun controllableLlmProvider(): ControllableLlmProvider = ControllableLlmProvider()

    @Bean
    fun convertDocumentUseCaseForReconversion(provider: ControllableLlmProvider): ConvertDocumentUseCase =
        ConvertDocumentUseCase(provider)

    /** 재변환 유스케이스도 실물이다 — 제품 조립(`DocumentConfiguration.reconvertUnitService`)과 같은 모양이다. */
    @Suppress("LongParameterList")
    @Bean
    fun reconvertUnitService(
        conversions: InMemoryConversionRepository,
        documents: InMemoryDocumentRepository,
        cipher: ContentCipher,
        convert: ConvertDocumentUseCase,
        transaction: TransactionRunner,
    ): ReconvertUnitService =
        ReconvertUnitService(
            conversions = conversions,
            documents = documents,
            cipher = cipher,
            convert = convert,
            transaction = transaction,
            callBudget = SLICE_RECONVERSION_CALL_BUDGET,
            concurrencyLimit = SLICE_RECONVERSION_CONCURRENCY,
        )

    @Bean
    fun inMemoryConversionFeedback(): InMemoryConversionFeedbackRepository = InMemoryConversionFeedbackRepository()

    /**
     * 파일럿 피드백 유스케이스도 실물이다 — 제품 조립(`DocumentConfiguration`)과 같은 모양이다.
     * `@WebMvcTest` 는 컨트롤러를 전부 슬라이스에 넣으므로 이 빈이 없으면 `/auth` 만 겨누는
     * 테스트도 컨텍스트 조립에서 멈춘다.
     */
    @Bean
    fun conversionFeedbackService(
        feedback: InMemoryConversionFeedbackRepository,
        cipher: ContentCipher,
        query: ConversionQueryService,
        transaction: TransactionRunner,
    ): ConversionFeedbackService =
        ConversionFeedbackService(
            feedback = feedback,
            cipher = cipher,
            query = query,
            transaction = transaction,
            editDistanceBudget = EditDistanceBudget(FeedbackProperties.DEFAULT_EDIT_DISTANCE_CELL_BUDGET),
        )

    /**
     * 슬라이스의 파일 조립. TXT 는 제품 함수, 나머지 형식은 본문 바이트만 담아 헤더·파일명을 잰다.
     * 실제 zip 은 `PackagedDocumentExporterTest` 와 실경로 테스트가 본다.
     */
    @Bean
    fun documentExporter(): DocumentExporter =
        DocumentExporter { title, body, format ->
            if (format == ExportFormat.TXT) {
                renderTxt(title, body)
            } else {
                exportFileOf(title, format, body.toByteArray(Charsets.UTF_8))
            }
        }

    /** 파일을 만드는 두 갈래 묶음. 제품 조립과 같은 모양이다. */
    @Bean
    fun exportRendering(
        reflection: OriginalReflection,
        exporter: DocumentExporter,
    ): ExportRendering = ExportRendering(reflection = reflection, exporter = exporter)

    @Bean
    fun conversionExportService(
        conversions: InMemoryConversionRepository,
        cipher: ContentCipher,
        maskedItems: MaskedItemReader,
        rendering: ExportRendering,
        transaction: TransactionRunner,
    ): ConversionExportService =
        ConversionExportService(
            conversions = conversions,
            cipher = cipher,
            maskedItems = maskedItems,
            rendering = rendering,
            transaction = transaction,
        )

    /**
     * 사전 조회(2.11.0, P0-5)도 `@WebMvcTest` 가 컨트롤러를 전부 슬라이스에 넣는 대상이다 —
     * 이 빈이 없으면 `/auth` 만 겨누는 테스트도 컨텍스트 조립에서 멈춘다(위 피드백 유스케이스와
     * 같은 주석).
     */
    @Bean
    fun fakeTermCandidateSource(): FakeTermCandidateSource = FakeTermCandidateSource()

    @Bean
    fun termLookupService(source: FakeTermCandidateSource): TermLookupService = TermLookupService(source)

    /**
     * 실물이다 — 60/분 기본 한도로 61번째 호출이 실제로 429 인지를 슬라이스가 직접 잰다
     * (`DictionaryLookupContractTest`). `Clock.systemUTC()` 를 쓴다 — 60초 창 안에서 시험이
     * 끝나므로 실제 시계로도 결정적이다.
     */
    @Bean
    fun lookupRateLimiter(): LookupRateLimiter =
        InMemorySlidingWindowLookupRateLimiter(limitPerMinute = LOOKUP_RATE_LIMIT_PER_MINUTE, clock = Clock.systemUTC())

    @Bean
    fun dictionaryAttributionProvider(): DictionaryAttributionProvider =
        DictionaryAttributionProvider {
            DictionaryAttribution(
                name = "테스트 사전",
                license = "테스트 라이선스",
                schemaVersion = "1.0.0",
            )
        }

    private companion object {
        /** 계약·`DictionaryLookupProperties.DEFAULT_RATE_LIMIT_PER_MINUTE` 와 같은 값. */
        const val LOOKUP_RATE_LIMIT_PER_MINUTE = 60

        /** 계약·`ReconversionProperties.DEFAULT_CALL_BUDGET` 과 같은 값. */
        const val SLICE_RECONVERSION_CALL_BUDGET = 20

        /** 계약·`ReconversionProperties.DEFAULT_CONCURRENCY` 와 같은 값. */
        const val SLICE_RECONVERSION_CONCURRENCY = 4
    }
}

/** 유일성을 키 일치로 판정한다 — `ix_users_email` 과 같은 축이다. */
class InMemoryUserRepository : UserRepository {
    private val byEmail = ConcurrentHashMap<String, StoredUser>()
    private val byId = ConcurrentHashMap<UUID, StoredUser>()

    override fun findByEmail(email: String): StoredUser? = byEmail[email]

    override fun findById(id: UUID): User? = byId[id]?.user

    override fun exists(id: UUID): Boolean = byId.containsKey(id)

    override fun create(
        email: String,
        passwordHash: PasswordHash,
    ): User = insert(email, passwordHash, emailVerified = false)

    override fun createWithoutPassword(
        email: String,
        emailVerified: Boolean,
    ): User = insert(email, passwordHash = null, emailVerified = emailVerified)

    private fun insert(
        email: String,
        passwordHash: PasswordHash?,
        emailVerified: Boolean,
    ): User {
        val verifiedAt = if (emailVerified) Instant.EPOCH else null
        val stored = StoredUser(User(UUID.randomUUID(), email, Instant.EPOCH, verifiedAt), passwordHash)
        if (byEmail.putIfAbsent(email, stored) != null) {
            throw EmailAlreadyRegisteredException("이미 가입된 이메일입니다")
        }
        byId[stored.user.id] = stored
        return stored.user
    }

    override fun updatePasswordHash(
        userId: UUID,
        passwordHash: PasswordHash,
    ) {
        val existing = byId[userId] ?: return
        val replaced = StoredUser(existing.user, passwordHash)
        byId[userId] = replaced
        byEmail[existing.user.email] = replaced
    }

    override fun markEmailVerified(userId: UUID) {
        val existing = byId[userId] ?: return
        if (existing.user.emailVerifiedAt != null) return
        val replaced = StoredUser(existing.user.copy(emailVerifiedAt = Instant.EPOCH), existing.passwordHash)
        byId[userId] = replaced
        byEmail[existing.user.email] = replaced
    }

    /** 슬라이스 테스트가 실물 인증 흐름을 건너뛰고 곧장 인증 완료로 만드는 자리. */
    fun verifyEmailFor(email: String) {
        byEmail[email]?.let { markEmailVerified(it.user.id) }
    }

    /** 계정 삭제 시나리오(M-3)를 위한 자리. 토큰은 유효한데 계정이 없는 상태를 만든다. */
    fun remove(userId: UUID) {
        val existing = byId.remove(userId) ?: return
        byEmail.remove(existing.user.email)
    }
}

/** 작업 공간 저장소의 가짜. */
class InMemoryWorkspaceRepository : WorkspaceRepository {
    private data class Row(
        val id: UUID,
        val ownerId: UUID,
        val name: String,
        val createdAt: Instant,
    )

    private val rows = mutableListOf<Row>()

    /** 테스트가 문서 수를 심는 자리. 실물에서는 `documents` 테이블이 답한다. */
    val documentCounts: MutableMap<UUID, Int> = mutableMapOf()

    override fun createDefault(userId: UUID): UUID = insert(userId, DEFAULT_WORKSPACE_NAME).id

    override fun listOwned(ownerId: UUID): List<WorkspaceListing> =
        rows
            .filter { it.ownerId == ownerId }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .map { WorkspaceListing(it.toWorkspace(), documentCounts[it.id] ?: 0) }

    override fun create(
        ownerId: UUID,
        name: String,
    ): Workspace = insert(ownerId, name).toWorkspace()

    override fun rename(
        ownerId: UUID,
        workspaceId: UUID,
        name: String,
    ): Workspace? {
        val index = rows.indexOfFirst { it.id == workspaceId && it.ownerId == ownerId }

        if (index < 0) {
            return null
        }
        requireUniqueName(ownerId, name, exceptId = workspaceId)
        val renamed = rows[index].copy(name = name)
        rows[index] = renamed
        return renamed.toWorkspace()
    }

    override fun lockForDeletion(
        ownerId: UUID,
        workspaceId: UUID,
    ): WorkspaceDeletionState? {
        val owned = rows.filter { it.ownerId == ownerId }
        if (owned.none { it.id == workspaceId }) {
            return null
        }
        return WorkspaceDeletionState(owned.size, documentCounts[workspaceId] ?: 0)
    }

    override fun delete(
        ownerId: UUID,
        workspaceId: UUID,
    ): Boolean = rows.removeIf { it.id == workspaceId && it.ownerId == ownerId }

    private fun insert(
        ownerId: UUID,
        name: String,
    ): Row {
        requireUniqueName(ownerId, name, exceptId = null)
        val row = Row(UUID.randomUUID(), ownerId, name, Instant.EPOCH.plusSeconds(rows.size.toLong()))
        rows += row
        return row
    }

    /** `uq_workspaces_user_id_name` 과 같은 축이다. */
    private fun requireUniqueName(
        ownerId: UUID,
        name: String,
        exceptId: UUID?,
    ) {
        if (rows.any { it.ownerId == ownerId && it.name == name && it.id != exceptId }) {
            throw ConflictException(DUPLICATE_WORKSPACE_NAME_MESSAGE)
        }
    }

    private fun Row.toWorkspace(): Workspace = Workspace(id, name, createdAt)
}

/** 해시를 흉내만 낸다 — Argon2 를 슬라이스 테스트에서 돌리지 않는다. */
class StubPasswordHasher : PasswordHasher {
    override fun hash(rawPassword: String): PasswordHash = PasswordHash("stub:$rawPassword")

    override fun verify(
        rawPassword: String,
        stored: PasswordHash,
    ): Boolean = stored.reveal() == "stub:$rawPassword"

    override fun needsRehash(stored: PasswordHash): Boolean = false

    /**
     * 어떤 비밀번호와도 일치하지 않는 값. [verify] 가 `"stub:"` 접두사를 요구하므로 이
     * 값으로는 절대 통과하지 않는다 — 계정 부재 경로가 실제 검증을 지나가는지를 슬라이스
     * 에서도 재려면 「일치하지 않음」이 성질로 성립해야 한다.
     */
    override fun dummyHash(): PasswordHash = PasswordHash("stub-dummy")
}

/** 토큰 문자열을 사용자 id 그대로 쓴다. 서명·만료는 여기서 재지 않는다. */
class StubAccessTokens : AccessTokens {
    /** 설정 미비 503 은 실물 설정에서만 잴 수 있다 — `AuthUnavailableContractTest` 가 맡는다. */
    override fun ensureConfigured() = Unit

    override fun issue(userId: UUID): IssuedAccessToken = IssuedAccessToken("stub-token:$userId", STUB_LIFETIME_SECONDS)

    override fun verify(token: String): UUID {
        val subject = token.removePrefix("stub-token:")
        return runCatching { UUID.fromString(subject) }
            .getOrElse { throw InvalidCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다") }
    }

    private companion object {
        /**
         * 계약값이 아니다. `expires_in` 의 계약 준수는 실물 설정에서 재야 하므로
         * `AuthEndpointReachTest` 가 맡는다 — 테스트 배선이 값을 정해 놓고 그 값을
         * 단언하면 아무것도 검증하지 않는 것이다.
         */
        const val STUB_LIFETIME_SECONDS = 1L
    }
}

/** `user_identities` 대역. */
class InMemoryUserIdentityRepository : UserIdentityRepository {
    private val byProvider = ConcurrentHashMap<Pair<SocialLoginProviderId, String>, UserIdentity>()

    override fun findByProviderIdentity(
        provider: SocialLoginProviderId,
        providerUserId: String,
    ): UserIdentity? = byProvider[provider to providerUserId]

    override fun findByUserAndProvider(
        userId: UUID,
        provider: SocialLoginProviderId,
    ): UserIdentity? = byProvider.values.firstOrNull { it.userId == userId && it.provider == provider }

    override fun findAllByUser(userId: UUID): List<UserIdentity> = byProvider.values.filter { it.userId == userId }

    override fun link(
        userId: UUID,
        provider: SocialLoginProviderId,
        providerUserId: String,
        email: String?,
        emailVerified: Boolean,
    ): UserIdentity {
        val identity = UserIdentity(UUID.randomUUID(), userId, provider, providerUserId)
        byProvider[provider to providerUserId] = identity
        return identity
    }
}

/** `oauth_states` 대역 — 실물(`JdbcOAuthStateStore`)과 같은 계약(단발 소비, `user_id` 바인딩)을 지킨다. */
class InMemoryOAuthStateStore : OAuthStateStore {
    private data class Entry(
        val provider: SocialLoginProviderId,
        val redirectUri: String,
        val nonce: String,
        val expiresAt: Instant,
        val userId: UUID?,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private var counter = 0

    override fun issue(
        provider: SocialLoginProviderId,
        redirectUri: String,
        ttl: java.time.Duration,
        userId: UUID?,
    ): OAuthChallenge {
        val state = "state-${++counter}"
        val nonce = "nonce-$counter"
        entries[state] = Entry(provider, redirectUri, nonce, Instant.now().plus(ttl), userId)
        return OAuthChallenge(state, nonce)
    }

    override fun consume(
        provider: SocialLoginProviderId,
        state: String,
        redirectUri: String,
    ): ConsumedOAuthState? =
        entries
            .remove(state)
            ?.takeIf {
                it.provider == provider && it.redirectUri == redirectUri && Instant.now().isBefore(it.expiresAt)
            }?.let { ConsumedOAuthState(it.nonce, it.userId) }
}

/**
 * Google 어댑터 대역. 실제 네트워크를 타지 않는다 — `code` 문자열 자체가 시나리오를
 * 인코딩한다(`sub|email|verified` 형식, `email` 을 비우면 이메일 없음). 특수값
 * `reject`·`unreachable` 은 각각 401·502 갈래를 만든다. redirect_uri 허용 목록은
 * [ALLOWED_REDIRECT_URI] 하나뿐이다 — 슬라이스 테스트가 허용 목록 밖 값을 보낼 때
 * 쓰는 고정 대조값이기도 하다.
 */
class FakeGoogleSocialLoginProvider : SocialLoginProvider {
    /** 컨트롤러가 검증에서 이미 끊긴 요청은 이 카운터를 건드리지 않아야 한다("제공자를 왕복하지 않는다"). */
    var exchangeCallCount = 0
        private set

    override fun supportsRedirectUri(redirectUri: String): Boolean = redirectUri == ALLOWED_REDIRECT_URI

    override fun authorizationUrl(
        state: String,
        nonce: String,
        redirectUri: String,
    ): String = "https://accounts.google.test/o/oauth2/v2/auth?state=$state&nonce=$nonce&redirect_uri=$redirectUri"

    override fun exchange(
        code: String,
        redirectUri: String,
        nonce: String,
    ): SocialIdentity {
        exchangeCallCount++
        if (code == "reject") {
            throw InvalidCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다")
        }
        if (code == "unreachable") {
            throw ExternalServiceUnavailableException("구글에 연결하지 못했습니다")
        }
        val parts = code.split("|")
        val providerUserId = parts.getOrNull(0) ?: error("잘못된 테스트 code: $code")
        val email = parts.getOrNull(1)?.ifBlank { null }
        val emailVerified = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: true
        return SocialIdentity(providerUserId, email, emailVerified)
    }

    companion object {
        const val ALLOWED_REDIRECT_URI = "http://localhost:5173/auth/google/callback"
    }
}

/**
 * 카카오 어댑터 대역 — backlog §1.4, 계약 2.13.0. [FakeGoogleSocialLoginProvider] 와 같은
 * `code` 인코딩(`sub|email|verified`, `reject`·`unreachable` 특수값)을 쓴다 — 계약이 보는
 * 결과 모양은 두 제공자가 같아서(OIDC·userinfo 대체 경로는 어댑터 내부 구현일 뿐) 슬라이스
 * 테스트 대역까지 그 경로를 흉내 낼 필요는 없다.
 */
class FakeKakaoSocialLoginProvider : SocialLoginProvider {
    var exchangeCallCount = 0
        private set

    override fun supportsRedirectUri(redirectUri: String): Boolean = redirectUri == ALLOWED_REDIRECT_URI

    override fun authorizationUrl(
        state: String,
        nonce: String,
        redirectUri: String,
    ): String = "https://kauth.kakao.test/oauth/authorize?state=$state&nonce=$nonce&redirect_uri=$redirectUri"

    override fun exchange(
        code: String,
        redirectUri: String,
        nonce: String,
    ): SocialIdentity {
        exchangeCallCount++
        if (code == "reject") {
            throw InvalidCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다")
        }
        if (code == "unreachable") {
            throw ExternalServiceUnavailableException("카카오에 연결하지 못했습니다")
        }
        val parts = code.split("|")
        val providerUserId = parts.getOrNull(0) ?: error("잘못된 테스트 code: $code")
        val email = parts.getOrNull(1)?.ifBlank { null }
        val emailVerified = parts.getOrNull(2)?.toBooleanStrictOrNull() ?: true
        return SocialIdentity(providerUserId, email, emailVerified)
    }

    companion object {
        const val ALLOWED_REDIRECT_URI = "http://localhost:5173/auth/kakao/callback"
    }
}

/**
 * 이메일 인증 코드 저장소 대역 — 실물(`JdbcVerificationCodeStore`)과 같은 계약(활성 코드
 * 하나·쿨다운·시도 상한)을 인메모리로 지킨다. 슬라이스는 시간을 실제로 흘려보내지 않으므로
 * 쿨다운은 [Instant.now] 대신 발급 횟수로 흉내 낸다 — `cooldownArmed` 가 참인 동안은
 * 다음 [issue] 가 무조건 쿨다운으로 거절된다(테스트가 직접 무장한다).
 */
class InMemoryVerificationCodeStore : VerificationCodeStore {
    private data class ActiveCode(
        val code: String,
        var attempts: Int = 0,
        var voided: Boolean = false,
    )

    private val active = ConcurrentHashMap<UUID, ActiveCode>()
    private var counter = 0

    /** 다음 [issue] 를 429 로 거절하게 만든다 — 쿨다운 케이스 전용 스위치. */
    var cooldownArmed: Boolean = false

    override fun issue(
        userId: UUID,
        ttl: Duration,
        cooldown: Duration,
    ): String {
        if (cooldownArmed) {
            throw RateLimitedException("잠시 후 다시 시도해주세요", cooldown.seconds.coerceAtLeast(1))
        }
        val code = String.format(Locale.ROOT, "%06d", ++counter % 1_000_000)
        active[userId] = ActiveCode(code)
        return code
    }

    override fun attempt(
        userId: UUID,
        code: String,
        maxAttempts: Int,
    ): Boolean {
        val current = active[userId]
        val matched = current != null && !current.voided && current.code == code
        when {
            current == null || current.voided -> {
                Unit
            }

            matched -> {
                active.remove(userId)
            }

            else -> {
                current.attempts++
                if (current.attempts >= maxAttempts) current.voided = true
            }
        }
        return matched
    }
}

/**
 * 사전 조회 컨트롤러 슬라이스 대역 — 실제 색인을 올리지 않고 고정 질의로 시나리오를 만든다.
 * [disabled] 는 [InMemoryVerificationCodeStore.cooldownArmed] 와 같은 관례의 스위치다 —
 * 다음 호출을 [NoTermCandidateSource] 와 같은 422 로 만든다.
 */
class FakeTermCandidateSource : TermCandidateSource {
    var disabled: Boolean = false

    override fun candidatesFor(query: TermQuery): List<TermCandidate> {
        if (disabled) {
            throw InvalidInputException(NoTermCandidateSource.LOOKUP_DISABLED_MESSAGE)
        }
        return FIXED_CANDIDATES[query.text] ?: emptyList()
    }

    companion object {
        /** 슬라이스 테스트가 아는 유일한 질의 — 계획 §3.6 이 미리 확인해 둔 실측 값과 같다. */
        const val KNOWN_QUERY = "구비서류"

        private val FIXED_CANDIDATES: Map<String, List<TermCandidate>> =
            mapOf(
                KNOWN_QUERY to
                    listOf(
                        TermCandidate(
                            term = "구비서류",
                            easyTerm = "준비할 서류",
                            strategy = ReplaceStrategy.SUBSTITUTE,
                            risk = RiskLevel.NONE,
                            definition = "신청에 필요한 서류",
                            caution = null,
                            tags = listOf("행정"),
                            examples =
                                listOf(
                                    DictionaryExample(
                                        before = "구비서류를 지참하세요",
                                        after = "준비할 서류를 가져오세요",
                                        isGolden = true,
                                    ),
                                ),
                            matchKind = TermMatchKind.EXACT,
                            applicable = true,
                            entryId = 2165,
                        ),
                    ),
            )
    }
}
