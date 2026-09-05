package kr.easydoc.application.conversion

import kr.easydoc.application.document.ConversionCiphertexts
import kr.easydoc.application.document.ConversionEnvelope
import kr.easydoc.application.document.FakeContentCipher
import kr.easydoc.application.document.FakeConversionRepository
import kr.easydoc.application.document.FakeDocumentOriginalRepository
import kr.easydoc.application.document.FakeQueryDocumentRepository
import kr.easydoc.application.document.RecordingTransactionRunner
import kr.easydoc.application.document.StoredConversion
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.SourceFormat
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.ReconversionBudgetExhaustedException
import kr.easydoc.core.exceptions.ReconversionConcurrencyExhaustedException
import kr.easydoc.core.llm.FakeLlmProvider
import kr.easydoc.core.llm.FakeLlmTurn
import kr.easydoc.core.llm.LlmCompletion
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** `ReconvertUnitService` — P0-4 S4 재변환. */
class ReconvertUnitServiceTest {
    private val transaction = RecordingTransactionRunner()
    private val cipher = FakeContentCipher(writeKeyVersion = 1, transaction = transaction)
    private val originals = FakeDocumentOriginalRepository(transaction)
    private val conversions = FakeConversionRepository(transaction, originals)
    private val documents = FakeQueryDocumentRepository(transaction)

    private val owner = UUID.randomUUID()

    /** 규칙 위반이 남아 있는 1차 변환 결과 — '금일'이 어려운 말 사전에 있다. */
    private val draftWithIssue = "금일 서류를 내세요."
    private val cleanText = "오늘 서류를 내세요."

    private fun reply(
        text: String,
        inputTokens: Int = 0,
        outputTokens: Int = 0,
    ) = FakeLlmTurn.Reply(
        text = text,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        finishReason = LlmFinishReason.END_TURN,
    )

    private fun service(
        provider: LlmProvider,
        callBudget: Int = DEFAULT_BUDGET,
        concurrencyLimit: Int = DEFAULT_CONCURRENCY,
    ) = ReconvertUnitService(
        conversions = conversions,
        documents = documents,
        cipher = cipher,
        convert = ConvertDocumentUseCase(provider),
        transaction = transaction,
        callBudget = callBudget,
        concurrencyLimit = concurrencyLimit,
    )

    /** 완료 상태 변환 한 건과 그 원문을 심는다 — 원본 단위 0 은 항상 [SOURCE_UNIT_0]. */
    private fun seedDone(status: ConversionStatus = ConversionStatus.DONE): UUID {
        val conversionId = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        conversions.owned[owner to conversionId] =
            StoredConversion(
                id = conversionId,
                documentId = documentId,
                status = status,
                sourceFormat = SourceFormat.TEXT,
                hasStoredOriginal = false,
                ciphertexts = ConversionCiphertexts(null, null, null),
                reviewedAt = null,
                feedbackSubmittedAt = null,
                missingPlaceholders = emptyList(),
                model = null,
                providerName = null,
                inputTokens = null,
                outputTokens = null,
                failureCode = null,
            )
        documents.seed(owner, documentId, "$SOURCE_UNIT_0\n$SOURCE_UNIT_1")
        return conversionId
    }

    @Test
    @DisplayName("행복 경로 — 보정 없이 통과하면 호출 1회, 예약 2에서 1이 환불된다")
    fun `행복 경로는 호출 1회다`() {
        val conversionId = seedDone()
        val provider = FakeLlmProvider(listOf(reply(cleanText)))

        val result =
            service(provider).reconvert(
                ownerId = owner,
                conversionId = conversionId,
                sourceUnitIndex = 0,
                easyUnitIndexes = listOf(0),
                easyTextFingerprint = FINGERPRINT,
            )

        assertThat(result.candidateText).isEqualTo(cleanText)
        assertThat(result.llmCallsUsed).isEqualTo(1)
        assertThat(result.sourceUnitIndex).isEqualTo(0)
        assertThat(result.easyUnitIndexes).isEqualTo(listOf(0))
        assertThat(result.easyTextFingerprint).isEqualTo(FINGERPRINT)
        assertThat(result.remainingCallBudget).isEqualTo(DEFAULT_BUDGET - 1)

        assertThat(conversions.reconversionBudgetOf(conversionId)).isEqualTo(0 to 1)
    }

    @Test
    @DisplayName("보정 경로 — 위반이 남아 있으면 보정을 불러 호출 2회를 쓴다")
    fun `보정 경로는 호출 2회를 쓴다`() {
        val conversionId = seedDone()
        val provider = FakeLlmProvider(List(10) { reply(draftWithIssue) })

        val result =
            service(provider).reconvert(owner, conversionId, 0, listOf(0), FINGERPRINT)

        assertThat(result.llmCallsUsed).isEqualTo(2)
        assertThat(result.remainingCallBudget).isEqualTo(DEFAULT_BUDGET - 2)
        assertThat(conversions.reconversionBudgetOf(conversionId)).isEqualTo(0 to 2)
    }

    @Test
    @DisplayName("보정을 건너뛴 나머지 1회는 환불된다 — 예약 2에서 사용 1만 남는다")
    fun `건너뛴 보정은 환불된다`() {
        val conversionId = seedDone()
        val provider = FakeLlmProvider(listOf(reply(cleanText)))

        service(provider).reconvert(owner, conversionId, 0, emptyList(), FINGERPRINT)

        val (reserved, used) = conversions.reconversionBudgetOf(conversionId)
        assertThat(reserved).withFailMessage("예약이 정산 뒤에도 남아 있다").isEqualTo(0)
        assertThat(used).isEqualTo(1)
    }

    @Test
    @DisplayName(
        "예산 소진 — 예약 자체가 실패하면 LLM 호출 0회이고 429 로 잔여 예산을 낸다",
    )
    fun `예산이 없으면 던지고 호출하지 않는다`() {
        val conversionId = seedDone()
        val provider = FakeLlmProvider(listOf(reply(cleanText)))
        val exhausted = service(provider, callBudget = 1)

        assertThatThrownBy { exhausted.reconvert(owner, conversionId, 0, listOf(0), FINGERPRINT) }
            .isInstanceOf(ReconversionBudgetExhaustedException::class.java)
            .extracting { (it as ReconversionBudgetExhaustedException).remainingCallBudget }
            .isEqualTo(1)

        assertThat(provider.calls).withFailMessage("예산 소진인데 LLM 을 호출했다").isEmpty()
    }

    @Test
    @DisplayName("provider 호출 실패 — 실제 사용량만 환불하고 502다(ExternalServiceUnavailableException)")
    fun `provider 실패는 실제 사용량만 환불하고 502다`() {
        val conversionId = seedDone()
        val provider = FakeLlmProvider(listOf(FakeLlmTurn.Fail(LlmProviderException("실패"))))

        assertThatThrownBy { service(provider).reconvert(owner, conversionId, 0, listOf(0), FINGERPRINT) }
            .isInstanceOf(ExternalServiceUnavailableException::class.java)

        // CompletionBudget.spend 는 호출 전에 spent 를 올린다 — 실패한 첫 호출도 시도
        // 자체가 실제 사용량 1회다(제공자가 과금했을 수도 있다). 예약 2에서 1만 환불된다.
        assertThat(conversions.reconversionBudgetOf(conversionId))
            .withFailMessage("provider 실패인데 실제 사용량(1회)만큼만 남기고 환불되지 않았다")
            .isEqualTo(0 to 1)
    }

    @Test
    @DisplayName("동시 실행 한도 0 — 예약 전액 환불하고 LLM 을 부르지 않는다")
    fun `동시성 한도가 0이면 호출하지 않고 전액 환불한다`() {
        val conversionId = seedDone()
        val provider = FakeLlmProvider(listOf(reply(cleanText)))

        assertThatThrownBy {
            service(provider, concurrencyLimit = 0).reconvert(owner, conversionId, 0, listOf(0), FINGERPRINT)
        }.isInstanceOf(ReconversionConcurrencyExhaustedException::class.java)

        assertThat(provider.calls).withFailMessage("동시성 한도 소진인데 LLM 을 호출했다").isEmpty()
        assertThat(conversions.reconversionBudgetOf(conversionId))
            .withFailMessage("동시성 한도 소진인데 예약이 전액 환불되지 않았다")
            .isEqualTo(0 to 0)
    }

    @Test
    @DisplayName("동시성 한도 1 — 먼저 permit 을 쥔 호출이 끝나기 전 두 번째 호출은 즉시 거절된다")
    fun `동시성 한도 1에서 두 번째 호출은 즉시 거절된다`() {
        val conversionA = seedDone()
        val conversionB = seedDone()
        val provider = BlockingLlmProvider()
        val svc = service(provider, concurrencyLimit = 1)
        val executor = Executors.newSingleThreadExecutor()

        try {
            val future =
                executor.submit<ReconvertUnitResult> {
                    svc.reconvert(owner, conversionA, 0, listOf(0), FINGERPRINT)
                }
            // A 가 permit 을 쥐고 provider.complete 안에서 블록할 때까지 기다린다.
            assertThat(provider.started.await(5, TimeUnit.SECONDS))
                .withFailMessage("스레드 A 가 5초 안에 provider.complete 에 진입하지 못했다")
                .isTrue()

            assertThatThrownBy {
                svc.reconvert(owner, conversionB, 0, listOf(0), FINGERPRINT)
            }.isInstanceOf(ReconversionConcurrencyExhaustedException::class.java)
            assertThat(conversions.reconversionBudgetOf(conversionB))
                .withFailMessage("B 는 permit 을 얻지 못했으니 예약이 전액 환불돼야 한다")
                .isEqualTo(0 to 0)

            provider.release.countDown()
            val result = future.get(5, TimeUnit.SECONDS)
            assertThat(result.llmCallsUsed).isEqualTo(1)
            assertThat(conversions.reconversionBudgetOf(conversionA)).isEqualTo(0 to 1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    @DisplayName("색인 범위 밖 — 422(InvalidInputException), LLM 을 부르지 않는다")
    fun `범위 밖 색인은 422다`() {
        val conversionId = seedDone()
        val provider = FakeLlmProvider(listOf(reply(cleanText)))

        assertThatThrownBy { service(provider).reconvert(owner, conversionId, 2, listOf(0), FINGERPRINT) }
            .isInstanceOf(InvalidInputException::class.java)
        assertThat(provider.calls).isEmpty()
        assertThat(conversions.reconversionBudgetOf(conversionId))
            .withFailMessage("범위 밖 색인인데 예산을 건드렸다")
            .isEqualTo(0 to 0)
    }

    @Test
    @DisplayName("완료 전 변환 — 409(ConflictException)")
    fun `완료 전이면 409다`() {
        val conversionId = seedDone(status = ConversionStatus.PROCESSING)
        val provider = FakeLlmProvider(listOf(reply(cleanText)))

        assertThatThrownBy { service(provider).reconvert(owner, conversionId, 0, listOf(0), FINGERPRINT) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(provider.calls).isEmpty()
    }

    @Test
    @DisplayName("남의 변환 — 404(NotFoundException)")
    fun `남의 변환은 404다`() {
        val conversionId = seedDone()
        val provider = FakeLlmProvider(listOf(reply(cleanText)))

        assertThatThrownBy {
            service(provider).reconvert(UUID.randomUUID(), conversionId, 0, listOf(0), FINGERPRINT)
        }.isInstanceOf(NotFoundException::class.java)
        assertThat(provider.calls).isEmpty()
    }

    @Test
    @DisplayName("PII 가 있는 원본 단위 — provider 에는 자리표시자만 가고 원문 숫자는 가지 않는다")
    fun `PII 단위의 재변환은 원문이 아니라 자리표시자를 provider 에 보낸다`() {
        val conversionId = UUID.randomUUID()
        val documentId = UUID.randomUUID()
        conversions.owned[owner to conversionId] =
            StoredConversion(
                id = conversionId,
                documentId = documentId,
                status = ConversionStatus.DONE,
                sourceFormat = SourceFormat.TEXT,
                hasStoredOriginal = false,
                ciphertexts = ConversionCiphertexts(null, null, null),
                reviewedAt = null,
                feedbackSubmittedAt = null,
                missingPlaceholders = emptyList(),
                model = null,
                providerName = null,
                inputTokens = null,
                outputTokens = null,
                failureCode = null,
            )
        documents.seed(owner, documentId, PII_SOURCE_UNIT)
        val provider = FakeLlmProvider(listOf(reply(cleanText)))

        service(provider).reconvert(owner, conversionId, 0, listOf(0), FINGERPRINT)

        val prompt =
            provider.calls
                .single()
                .prompt.user
        assertThat(prompt).contains("[[주민등록번호1]]")
        assertThat(prompt).doesNotContain("900101-1234567")
    }

    @Test
    @DisplayName("지문 형식 위반 — 422(InvalidInputException), LLM 을 부르지 않고 예산도 건드리지 않는다")
    fun `잘못된 지문 형식은 422다`() {
        val conversionId = seedDone()
        val provider = FakeLlmProvider(listOf(reply(cleanText)))

        assertThatThrownBy {
            service(provider).reconvert(owner, conversionId, 0, listOf(0), "not-a-valid-fingerprint")
        }.isInstanceOf(InvalidInputException::class.java)

        assertThat(provider.calls).withFailMessage("지문 형식 위반인데 LLM 을 호출했다").isEmpty()
        assertThat(conversions.reconversionBudgetOf(conversionId))
            .withFailMessage("지문 형식 위반인데 예산을 건드렸다")
            .isEqualTo(0 to 0)
    }

    /**
     * 첫 호출을 블록해 permit 을 쥔 채로 붙잡아 두는 테스트 대역 — `FakeLlmProvider` 는
     * 큐 소진 시 즉시 던지거나 반환할 뿐 블록할 수 없어 진짜 동시성 시나리오를 못 만든다.
     */
    private class BlockingLlmProvider : LlmProvider {
        override val name = "fake"
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun complete(
            prompt: LlmPrompt,
            options: LlmOptions,
        ): LlmCompletion {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            return LlmCompletion(
                text = "결과",
                provider = "fake",
                model = "fake-model",
                finishReason = LlmFinishReason.END_TURN,
            )
        }
    }

    private companion object {
        const val DEFAULT_BUDGET = 20
        const val DEFAULT_CONCURRENCY = 4
        val FINGERPRINT = "a".repeat(64)
        const val SOURCE_UNIT_0 = "금일 서류를 제출하십시오."
        const val SOURCE_UNIT_1 = "두 번째 줄입니다."
        const val PII_SOURCE_UNIT = "신청자 900101-1234567 님"
    }
}
