package kr.easydoc.application.illustration.generation

import kr.easydoc.application.illustration.suggestion.PassThroughCipher
import kr.easydoc.application.illustration.suggestion.RecordingSuggestionResults
import kr.easydoc.application.illustration.suggestion.StoredIllustrationSuggestionResult
import kr.easydoc.application.illustration.suggestion.suggestionSet
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.illustration.generation.IllustrationGenerationStorageCodec
import kr.easydoc.core.illustration.generation.illustrationConfigurationRevision
import kr.easydoc.core.illustration.suggestion.ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionStorageCodec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.ZoneOffset

class IllustrationGenerationJobServiceTest {
    private val jobs = FakeGenerationJobs()
    private val suggestions = RecordingSuggestionResults()
    private val credits = RecordingGenerationCredits()
    private val cipher = PassThroughCipher()

    init {
        suggestions.latest =
            StoredIllustrationSuggestionResult(
                GENERATION_SUGGESTION_RESULT,
                GENERATION_JOB,
                GENERATION_CONVERSION,
                3,
                cipher.encrypt(
                    PlainBody(IllustrationSuggestionStorageCodec.encode(suggestionSet())),
                    GENERATION_SUGGESTION_RESULT,
                    EncryptedField.ILLUSTRATION_SUGGESTION_RESULT,
                ),
            )
    }

    @Test
    fun `최신 검증 제안으로 서버 프롬프트를 만들고 한 작업과 예약을 저장한다`() {
        val created =
            service().create(
                GENERATION_OWNER,
                GENERATION_CONVERSION,
                GENERATION_REQUEST,
                3,
                generationInput().metadata.suggestionId,
                ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
                illustrationConfigurationRevision(generationConfiguration()),
                generationConfiguration(),
            )

        assertThat(created.job.basedOnContentRevision).isEqualTo(3)
        assertThat(credits.reserveCalls).isEqualTo(1)
        assertThat(credits.reservedAmounts.single().amount).isEqualTo(BigDecimal("0.5"))
        val stored = jobs.rows.values.single()
        val input =
            IllustrationGenerationStorageCodec.decode(
                cipher
                    .decrypt(
                        stored.inputPayload,
                        stored.jobId,
                        EncryptedField.ILLUSTRATION_GENERATION_JOB_INPUT,
                    ).value,
            )
        assertThat(input.finalPrompt).contains("신청서를 제출합니다")
        assertThat(input.finalPrompt).contains("Return one 1024x1024 PNG")
        assertThat(input.metadata.altText).isEqualTo(generationConfiguration().altText)
    }

    @Test
    fun `같은 요청 키와 입력은 같은 작업이고 다른 입력은 409다`() {
        val service = service()
        val first = create(service)
        val again = create(service)

        assertThat(again.job.jobId).isEqualTo(first.job.jobId)
        assertThat(credits.reserveCalls).isEqualTo(1)
        assertThatThrownBy {
            service.create(
                GENERATION_OWNER,
                GENERATION_CONVERSION,
                GENERATION_REQUEST,
                4,
                generationInput().metadata.suggestionId,
                ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
                illustrationConfigurationRevision(generationConfiguration()),
                generationConfiguration(),
            )
        }.isInstanceOf(ConflictException::class.java)
        assertThat(jobs.rows).hasSize(1)
    }

    @Test
    fun `최신 결과가 아니거나 구성 해시가 다륾면 접수하지 않는다`() {
        val service = service()
        suggestions.latest = suggestions.latest!!.copy(basedOnContentRevision = 2)
        assertThatThrownBy { create(service) }.isInstanceOf(ConflictException::class.java)
        assertThat(jobs.rows).isEmpty()

        suggestions.latest = suggestions.latest!!.copy(basedOnContentRevision = 3)
        assertThatThrownBy {
            service.create(
                GENERATION_OWNER,
                GENERATION_CONVERSION,
                GENERATION_REQUEST,
                3,
                generationInput().metadata.suggestionId,
                ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
                "0".repeat(64),
                generationConfiguration(),
            )
        }.isInstanceOf(ConflictException::class.java)
        assertThat(jobs.rows).isEmpty()
    }

    @Test
    fun `0 크레딧은 fake 허용 설정에서만 접수된다`() {
        assertThatThrownBy {
            service(creditsPerImage = BigDecimal.ZERO, zeroAllowed = false).let { create(it) }
        }.isInstanceOf(ConfigurationException::class.java)

        val created = service(creditsPerImage = BigDecimal.ZERO, zeroAllowed = true).let { create(it) }
        assertThat(created.job.reservedCredits).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `기능이 꺼져 있어도 소유자는 비활성 상태를 조회할 수 있고 접수는 차단된다`() {
        val disabled = service(enabled = false)
        assertThat(disabled.list(GENERATION_OWNER, GENERATION_CONVERSION).enabled).isFalse()
        assertThatThrownBy { create(disabled) }
            .isInstanceOf(kr.easydoc.core.exceptions.NotFoundException::class.java)
    }

    @Test
    fun `활성 여부는 실행 모드별 이용량과 가격 설정까지 반영한다`() {
        fun available(service: IllustrationGenerationJobService) =
            service.list(GENERATION_OWNER, GENERATION_CONVERSION).enabled
        assertThat(available(service(creditsPerImage = null))).isFalse()
        assertThat(available(service(creditsPerImage = BigDecimal.ZERO))).isFalse()
        assertThat(available(service(providerPricingConfigured = false))).isFalse()
        assertThat(available(service())).isTrue()
        assertThat(available(service(creditsPerImage = BigDecimal.ZERO, zeroAllowed = true))).isTrue()
    }

    @Test
    fun `문서 한도는 모든 요청에 적용하되 같은 멱등 요청은 다시 센지 않는다`() {
        val service = service()
        val first = create(service)
        jobs.countOverride = MAX_GENERATION_JOBS_PER_DOCUMENT
        assertThat(create(service).job.jobId).isEqualTo(first.job.jobId)
        assertThatThrownBy {
            service.create(
                GENERATION_OWNER,
                GENERATION_CONVERSION,
                java.util.UUID.randomUUID(),
                3,
                generationInput().metadata.suggestionId,
                ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
                illustrationConfigurationRevision(generationConfiguration()),
                generationConfiguration(),
            )
        }.isInstanceOf(ConflictException::class.java).hasMessage(GENERATION_DOCUMENT_LIMIT_MESSAGE)
        assertThat(credits.reserveCalls).isEqualTo(1)
    }

    @Test
    fun `실제 provider 가격이 없으면 고객 차감량이 있어도 접수하지 않는다`() {
        assertThatThrownBy {
            service(providerPricingConfigured = false).let { create(it) }
        }.isInstanceOf(ConfigurationException::class.java)
        assertThat(jobs.rows).isEmpty()
    }

    private fun create(service: IllustrationGenerationJobService) =
        service.create(
            GENERATION_OWNER,
            GENERATION_CONVERSION,
            GENERATION_REQUEST,
            3,
            generationInput().metadata.suggestionId,
            ILLUSTRATION_SUGGESTION_ANALYSIS_VERSION,
            illustrationConfigurationRevision(generationConfiguration()),
            generationConfiguration(),
        )

    private fun service(
        creditsPerImage: BigDecimal? = BigDecimal("0.5"),
        zeroAllowed: Boolean = false,
        providerPricingConfigured: Boolean = true,
        enabled: Boolean = true,
    ) = IllustrationGenerationJobService(
        enabled = enabled,
        creditsPerImage = creditsPerImage,
        zeroCreditsAllowed = zeroAllowed,
        providerPricingConfigured = providerPricingConfigured,
        jobs = jobs,
        suggestions = suggestions,
        credits = credits,
        cipher = cipher,
        transaction = DirectGenerationTransaction(),
        clock = Clock.fixed(GENERATION_NOW, ZoneOffset.UTC),
    )
}
