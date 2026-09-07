package kr.easydoc.application.conversion

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountRepository
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.credit.NoopCreditAccountRepository
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.mail.EmailAddress
import kr.easydoc.application.mail.MailDelivery
import kr.easydoc.application.mail.MailSender
import kr.easydoc.application.mail.OutboundMail
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.FakeLlmProvider
import kr.easydoc.core.llm.FakeLlmTurn
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmFinishReason
import kr.easydoc.core.llm.LlmOptions
import kr.easydoc.core.llm.LlmPrompt
import kr.easydoc.core.llm.LlmProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/** worker 수직 흐름 — 리스·트랜잭션 밖 LLM·CAS. */
class ProcessConversionJobTest {
    @Test
    @DisplayName("집을 작업이 없으면 IDLE 이다")
    fun `빈 큐는 가만히 있는다`() {
        val world = World(lease = null)

        assertThat(world.jobs.processNext()).isEqualTo(ConversionJobOutcome.IDLE)
        assertThat(world.provider.calls).isEmpty()
    }

    @Nested
    @DisplayName("획득·처리·완료")
    inner class HappyPath {
        @Test
        @DisplayName("pending 을 processing 으로 올린 뒤에야 LLM 을 부른다")
        fun `처리 중 상태가 LLM 보다 앞선다`() {
            val world = World()
            var statusAtCall: ConversionStatus? = null
            world.provider.onComplete = {
                statusAtCall = world.work.status
                assertThat(world.transaction.depth).isZero()
            }

            val outcome = world.jobs.processNext()

            assertThat(outcome).isEqualTo(ConversionJobOutcome.COMPLETED)
            assertThat(statusAtCall).isEqualTo(ConversionStatus.PROCESSING)
            assertThat(world.work.status).isEqualTo(ConversionStatus.DONE)
            assertThat(world.leases.completed).containsExactly(world.lease)
        }

        @Test
        @DisplayName("완료되면 소유자에게 알림 메일을 정확히 한 번 보낸다")
        fun `완료는 알림을 한 번 보낸다`() {
            val world = World()

            world.jobs.processNext()

            assertThat(world.mailSender.sent).hasSize(1)
            assertThat(world.notificationStore.markNotifiedCalls).containsExactly(world.conversionId)
        }

        @Test
        @DisplayName("완료 결과의 본문은 변환 행 식별자로 봉인한다")
        fun `결과 결속은 변환 식별자다`() {
            val world = World()

            world.jobs.processNext()

            val fields = world.cipher.sealed.map { it.third }
            assertThat(fields).contains(EncryptedField.CONVERSION_EASY_TEXT)
            assertThat(world.cipher.sealed.filter { it.third != EncryptedField.DOCUMENT_SOURCE_TEXT })
                .allMatch { it.second == world.conversionId }
        }

        @Test
        @DisplayName("LLM 결과의 CRLF 도 저장 전에 LF 로 통일된다 — `splitUnits` 가 `\\n` 만 본다")
        fun `LLM 결과 CRLF 가 LF 로 저장된다`() {
            val world = World(provider = FakeLlmProvider.replying("첫 줄\r\n둘째 줄"))

            world.jobs.processNext()

            val easyText = world.cipher.sealed.single { it.third == EncryptedField.CONVERSION_EASY_TEXT }
            assertThat(easyText.first)
                .describedAs("`\\r` 이 남으면 segment_map·문체 판정·재변환이 원문과 어긋난 줄 수를 본다")
                .isEqualTo("첫 줄\n둘째 줄")
        }
    }

    @Nested
    @DisplayName("트랜잭션 경계")
    inner class TransactionBoundary {
        @Test
        @DisplayName("LLM 호출 시점의 트랜잭션 깊이는 0 이다")
        fun `LLM 은 트랜잭션 밖이다`() {
            val world = World()
            val depths = mutableListOf<Int>()
            world.provider.onComplete = { depths += world.transaction.depth }

            world.jobs.processNext()

            assertThat(depths).isNotEmpty().allMatch { it == 0 }
        }
    }

    @Nested
    @DisplayName("실패·재시도")
    inner class FailureAndRetry {
        @Test
        @DisplayName("호출 실패는 상한 미만이면 대기열로 되돌린다")
        fun `재시도 가능한 실패는 다시 집는다`() {
            val world =
                World(
                    provider = FakeLlmProvider(listOf(FakeLlmTurn.Fail(LlmProviderException("호출 실패")))),
                    attempts = 1,
                )

            assertThat(world.jobs.processNext()).isEqualTo(ConversionJobOutcome.RETRY_SCHEDULED)
            assertThat(world.work.status).isEqualTo(ConversionStatus.PENDING)
            assertThat(world.leases.retried).containsExactly(world.lease)
            assertThat(world.leases.failed).isEmpty()
        }

        @Test
        @DisplayName("상한에 닿은 호출 실패는 변환을 failed 로 확정한다")
        fun `상한이면 실패로 끝낸다`() {
            val world =
                World(
                    provider = FakeLlmProvider(listOf(FakeLlmTurn.Fail(LlmProviderException("호출 실패")))),
                    attempts = 3,
                )

            assertThat(world.jobs.processNext()).isEqualTo(ConversionJobOutcome.FAILED)
            assertThat(world.work.status).isEqualTo(ConversionStatus.FAILED)
            assertThat(world.work.failureCode).isEqualTo(ConversionFailureKind.PROVIDER_ERROR.failureCode)
            assertThat(world.leases.failed).containsExactly(world.lease)
            assertThat(world.mailSender.sent).isEmpty()
        }

        @Test
        @DisplayName("획득 시점에 이미 상한을 넘긴 작업은 LLM 없이 실패로 확정한다")
        fun `중단 회수가 상한을 넘기면 즉시 실패다`() {
            val world = World(exhausted = true)

            assertThat(world.jobs.processNext()).isEqualTo(ConversionJobOutcome.FAILED)
            assertThat(world.provider.calls).isEmpty()
            assertThat(world.work.status).isEqualTo(ConversionStatus.FAILED)
            assertThat(world.work.failureCode).isEqualTo(ProcessConversionJob.ATTEMPTS_EXHAUSTED_FAILURE_CODE)
        }

        @Test
        @DisplayName("절단은 재시도하지 않는다")
        fun `절단은 즉시 실패다`() {
            val world =
                World(
                    provider =
                        FakeLlmProvider(
                            listOf(
                                FakeLlmTurn.Reply(
                                    text = "쉬운 글이 도중에",
                                    finishReason = LlmFinishReason.MAX_TOKENS,
                                ),
                            ),
                        ),
                    attempts = 1,
                )

            assertThat(world.jobs.processNext()).isEqualTo(ConversionJobOutcome.FAILED)
            assertThat(world.work.failureCode).isEqualTo(ConversionFailureKind.TRUNCATED.failureCode)
            assertThat(world.leases.retried).isEmpty()
        }
    }

    @Nested
    @DisplayName("fencing / CAS")
    inner class Fencing {
        @Test
        @DisplayName("리스를 잃은 뒤에는 완료 결과를 쓰지 않는다")
        fun `잃은 리스는 덮어쓰지 않는다`() {
            val world = World()
            world.leases.held = false

            assertThat(world.jobs.processNext()).isEqualTo(ConversionJobOutcome.DROPPED)
            assertThat(world.work.successWrites).isEmpty()
            assertThat(world.work.status).isEqualTo(ConversionStatus.PROCESSING)
            assertThat(world.mailSender.sent).isEmpty()
        }

        @Test
        @DisplayName("이미 끝난 행은 saveSuccess 가 거절하면 본문을 바꾸지 않는다")
        fun `끝난 행은 덮지 않는다`() {
            val world = World()
            world.work.saveSuccessSucceeds = false

            assertThat(world.jobs.processNext()).isEqualTo(ConversionJobOutcome.DROPPED)
            assertThat(world.work.status).isNotEqualTo(ConversionStatus.DONE)
        }
    }

    @Test
    @DisplayName("하트비트는 LLM 구간에서 리스를 연장한다")
    fun `처리 중에 갱신한다`() {
        val world = World()

        world.jobs.processNext()

        assertThat(world.heartbeat.renewed).containsExactly(world.lease)
    }

    @Nested
    @DisplayName("LLM 호출 원장 (U1)")
    inner class LlmCallLedgerWrites {
        @Test
        @DisplayName("완료는 완료 저장과 같은 트랜잭션에서 원장 항목 하나를 남긴다")
        fun `완료는 원장 한 행을 남긴다`() {
            val world = World()
            var depthWhenAppended: Int? = null
            world.ledger.onAppend = { depthWhenAppended = world.transaction.depth }

            world.jobs.processNext()

            assertThat(world.ledger.appended).hasSize(1)
            val entry = world.ledger.appended.single()
            assertThat(entry.conversionId).isEqualTo(world.conversionId)
            assertThat(entry.documentId).isEqualTo(world.documentId)
            assertThat(entry.workspaceId).isEqualTo(world.workspaceId)
            assertThat(entry.userId).isEqualTo(world.userId)
            assertThat(entry.record.purpose).isEqualTo(LlmCallPurpose.CONVERT)
            assertThat(depthWhenAppended).withFailMessage("원장 쓰기가 완료 저장과 같은 트랜잭션에 있지 않다").isEqualTo(1)
        }

        @Test
        @DisplayName("provider 예외 실패는 원장에 아무것도 남기지 않는다")
        fun `provider 예외 실패는 0행이다`() {
            val world =
                World(
                    provider = FakeLlmProvider(listOf(FakeLlmTurn.Fail(LlmProviderException("호출 실패")))),
                    attempts = 3,
                )

            world.jobs.processNext()

            assertThat(world.ledger.appended).isEmpty()
        }

        @Test
        @DisplayName("절단(재시도 불가) 실패도 실제 토큰을 썼으니 원장에 한 행을 남긴다")
        fun `절단 실패는 실제 사용량을 원장에 남긴다`() {
            val world =
                World(
                    provider =
                        FakeLlmProvider(
                            listOf(
                                FakeLlmTurn.Reply(
                                    text = "쉬운 글이 도중에",
                                    inputTokens = 10,
                                    outputTokens = 5,
                                    finishReason = LlmFinishReason.MAX_TOKENS,
                                ),
                            ),
                        ),
                    attempts = 1,
                )

            world.jobs.processNext()

            val appended = world.ledger.appended
            assertThat(appended).hasSize(1)
            val onlyEntry = appended.single()
            assertThat(onlyEntry.record.inputTokens).isEqualTo(10)
        }

        @Test
        @DisplayName("REFUSAL 은 재시도 대상(PROVIDER_ERROR)이어도 실제 토큰을 썼으므로 재시도 예정과 별개로 원장에 남는다")
        fun `REFUSAL 은 재시도 예정이어도 원장에 남는다`() {
            val world =
                World(
                    provider =
                        FakeLlmProvider(
                            listOf(
                                FakeLlmTurn.Reply(
                                    text = "",
                                    inputTokens = 7,
                                    outputTokens = 0,
                                    finishReason = LlmFinishReason.REFUSAL,
                                ),
                            ),
                        ),
                    attempts = 1,
                )

            val outcome = world.jobs.processNext()

            assertThat(outcome).isEqualTo(ConversionJobOutcome.RETRY_SCHEDULED)
            val appended = world.ledger.appended
            assertThat(appended).hasSize(1)
            val onlyEntry = appended.single()
            assertThat(onlyEntry.record.inputTokens).isEqualTo(7)
        }
    }

    @Nested
    @DisplayName("크레딧 계정 (C1)")
    inner class CreditAccounting {
        @Test
        @DisplayName("완료는 예약을 소비로 확정한다")
        fun `완료는 소비를 확정한다`() {
            val world = World()

            world.jobs.processNext()

            assertThat(world.creditRepository.consumeCalls).containsExactly(world.conversionId to FAKE_CREDITS_RESERVED)
            assertThat(world.creditRepository.releaseCalls).isEmpty()
        }

        @Test
        @DisplayName("재시도 예정 실패는 소비도 해제도 부르지 않는다")
        fun `재시도 예정은 손대지 않는다`() {
            val world =
                World(
                    provider = FakeLlmProvider(listOf(FakeLlmTurn.Fail(LlmProviderException("호출 실패")))),
                    attempts = 1,
                )

            assertThat(world.jobs.processNext()).isEqualTo(ConversionJobOutcome.RETRY_SCHEDULED)
            assertThat(world.creditRepository.consumeCalls).isEmpty()
            assertThat(world.creditRepository.releaseCalls).isEmpty()
        }

        @Test
        @DisplayName("상한 도달 영구 실패는 예약을 해제한다")
        fun `영구 실패는 해제한다`() {
            val world =
                World(
                    provider = FakeLlmProvider(listOf(FakeLlmTurn.Fail(LlmProviderException("호출 실패")))),
                    attempts = 3,
                )

            assertThat(world.jobs.processNext()).isEqualTo(ConversionJobOutcome.FAILED)
            assertThat(world.creditRepository.releaseCalls).containsExactly(world.conversionId to FAKE_CREDITS_RESERVED)
            assertThat(world.creditRepository.consumeCalls).isEmpty()
        }

        @Test
        @DisplayName("획득 시점에 이미 상한을 넘긴 작업도 예약을 해제한다")
        fun `조기 소진도 해제한다`() {
            val world = World(exhausted = true)

            assertThat(world.jobs.processNext()).isEqualTo(ConversionJobOutcome.FAILED)
            assertThat(world.creditRepository.releaseCalls).containsExactly(world.conversionId to FAKE_CREDITS_RESERVED)
        }

        @Test
        @DisplayName("0 크레딧(V15 이전 문서)의 완료는 저장소를 부르지 않는다")
        fun `레거시 문서는 소비를 부르지 않는다`() {
            val world = World(creditsReserved = 0)

            world.jobs.processNext()

            assertThat(world.creditRepository.consumeCalls).isEmpty()
        }

        @Test
        @DisplayName("0 크레딧(V15 이전 문서)의 영구 실패는 저장소를 부르지 않는다")
        fun `레거시 문서는 해제를 부르지 않는다`() {
            val world =
                World(
                    provider = FakeLlmProvider(listOf(FakeLlmTurn.Fail(LlmProviderException("호출 실패")))),
                    attempts = 3,
                    creditsReserved = 0,
                )

            world.jobs.processNext()

            assertThat(world.creditRepository.releaseCalls).isEmpty()
        }
    }

    private class World(
        source: String = "복지 급여를 안내합니다.",
        provider: FakeLlmProvider = FakeLlmProvider.replying("오늘 서류를 내세요."),
        lease: ConversionJobLease? = ConversionJobLease(UUID.randomUUID(), OWNER, attempts = 1),
        attempts: Int = 1,
        exhausted: Boolean = false,
        creditsReserved: Int = FAKE_CREDITS_RESERVED,
    ) {
        val conversionId: UUID = lease?.conversionId ?: UUID.randomUUID()
        val documentId: UUID = UUID.randomUUID()
        val workspaceId: UUID = UUID.randomUUID()
        val userId: UUID = UUID.randomUUID()
        val lease: ConversionJobLease? =
            lease?.let { ConversionJobLease(conversionId, it.owner, attempts) }
        val transaction = RecordingDepth()
        val cipher = RecordingCipher(transaction)
        val sourceSealed = cipher.encrypt(PlainBody(source), documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)
        val leases = FakeLeases(this.lease, transaction, exhausted)
        val work = FakeWork(conversionId, documentId, workspaceId, userId, sourceSealed, transaction, creditsReserved)
        val provider = SpyingProvider(provider)
        val heartbeat = RenewingHeartbeat(leases)
        val notificationStore = FakeNotificationStore()
        val mailSender = RecordingMailSender()
        val notifier = ConversionCompletedNotifier(notificationStore, mailSender, "http://localhost:5173")
        val ledger = RecordingLedger()
        val creditRepository = RecordingCreditAccountRepository()
        val credits = CreditAccountService(creditRepository, enforced = false)
        val jobs =
            ProcessConversionJob(
                stores =
                    ConversionWorkerStores(
                        leases = leases,
                        work = work,
                        cipher = cipher,
                    ),
                convert = ConvertDocumentUseCase(this.provider),
                transaction = transaction,
                runtime =
                    ConversionWorkerRuntime(
                        heartbeat = heartbeat,
                        policy =
                            ConversionWorkerPolicy(
                                owner = OWNER,
                                leaseDuration = Duration.ofMinutes(2),
                                maxAttempts = 3,
                                retryBackoff = Duration.ofSeconds(5),
                            ),
                    ),
                notifier = notifier,
                ledger = ledger,
                creditAccountService = credits,
            )
    }

    /**
     * 소비·해제 호출을 기록하는 대역 — C1 검증용. `consume`·`release` 만 재정의하고 나머지는
     * [NoopCreditAccountRepository] 에 위임한다(리뷰 MEDIUM-11).
     */
    private class RecordingCreditAccountRepository : CreditAccountRepository by NoopCreditAccountRepository {
        val consumeCalls = mutableListOf<Pair<UUID, Int>>()
        val releaseCalls = mutableListOf<Pair<UUID, Int>>()

        override fun consume(
            workspaceId: UUID,
            ownerId: UUID,
            documentId: UUID,
            conversionId: UUID,
            amount: Credits,
        ) {
            consumeCalls += conversionId to amount.amount
        }

        override fun release(
            workspaceId: UUID,
            ownerId: UUID,
            documentId: UUID,
            conversionId: UUID,
            amount: Credits,
        ) {
            releaseCalls += conversionId to amount.amount
        }
    }

    /** 원장에 실제로 쓴 항목을 기록하는 대역 — U1 검증용. */
    private class RecordingLedger : LlmCallLedger {
        val appended = mutableListOf<LlmCallEntry>()
        var onAppend: () -> Unit = {}

        override fun append(entries: List<LlmCallEntry>) {
            onAppend()
            appended += entries
        }
    }

    private class FakeNotificationStore : ConversionNotificationStore {
        val markNotifiedCalls = mutableListOf<UUID>()

        override fun findTarget(conversionId: UUID): ConversionNotificationTarget =
            ConversionNotificationTarget(
                documentTitle = "제목",
                ownerEmail = EmailAddress.of("owner@example.com"),
                alreadyNotified = false,
            )

        override fun markNotified(conversionId: UUID): Boolean {
            markNotifiedCalls += conversionId
            return true
        }
    }

    private class RecordingMailSender : MailSender {
        val sent = mutableListOf<OutboundMail>()

        override fun send(message: OutboundMail): MailDelivery {
            sent += message
            return MailDelivery.Sent()
        }
    }

    private class RecordingDepth : TransactionRunner {
        var depth: Int = 0
            private set

        override fun <T> inTransaction(block: () -> T): T {
            depth++
            return try {
                block()
            } finally {
                depth--
            }
        }
    }

    private class RecordingCipher(private val transaction: RecordingDepth) : ContentCipher {
        override val writeScheme: String = EncryptionScheme.AES_256_GCM_V1
        override val writeKeyVersion: Int = 1
        val sealed = mutableListOf<Triple<String, UUID, EncryptedField>>()
        val depthWhenDecrypted = mutableListOf<Int>()

        /** 바이트 짝만 구현한다 — 문자열 짝은 [ContentCipher] 의 기본 구현을 탄다. */
        override fun encryptBytes(
            plain: PlainBytes,
            record: UUID,
            field: EncryptedField,
        ): EncryptedContent {
            sealed += Triple(String(plain.value, StandardCharsets.UTF_8), record, field)
            return EncryptedContent(plain.value, writeScheme, writeKeyVersion)
        }

        override fun decryptBytes(
            content: EncryptedContent,
            record: UUID,
            field: EncryptedField,
        ): PlainBytes {
            depthWhenDecrypted += transaction.depth
            return PlainBytes(content.bytes)
        }
    }

    private class FakeLeases(
        private val next: ConversionJobLease?,
        private val transaction: RecordingDepth,
        private val exhausted: Boolean = false,
    ) : ConversionJobLeasePort {
        var held: Boolean = true
        val completed = mutableListOf<ConversionJobLease>()
        val retried = mutableListOf<ConversionJobLease>()
        val failed = mutableListOf<ConversionJobLease>()
        val renewed = mutableListOf<ConversionJobLease>()
        val depthWhenAcquired = mutableListOf<Int>()

        override fun acquire(
            owner: String,
            leaseDuration: Duration,
            maxAttempts: Int,
        ): ConversionAcquire {
            depthWhenAcquired += transaction.depth
            return when {
                next == null -> ConversionAcquire.Empty
                exhausted -> ConversionAcquire.Exhausted(next.conversionId)
                else -> ConversionAcquire.Held(next)
            }
        }

        override fun renew(
            lease: ConversionJobLease,
            leaseDuration: Duration,
        ): Boolean {
            if (!held) return false
            renewed += lease
            return true
        }

        override fun lockIfHeld(lease: ConversionJobLease): Boolean = held

        override fun complete(lease: ConversionJobLease): Boolean {
            if (!held) return false
            completed += lease
            return true
        }

        override fun retry(
            lease: ConversionJobLease,
            delay: Duration,
        ): Boolean {
            retried += lease
            return true
        }

        override fun fail(lease: ConversionJobLease): Boolean {
            failed += lease
            return true
        }
    }

    @Suppress("LongParameterList")
    private class FakeWork(
        private val conversionId: UUID,
        private val documentId: UUID,
        private val workspaceId: UUID,
        private val userId: UUID,
        private val sourceText: EncryptedContent,
        private val transaction: RecordingDepth,
        private val creditsReserved: Int = FAKE_CREDITS_RESERVED,
    ) : ConversionWorkStore {
        var status: ConversionStatus = ConversionStatus.PENDING
        var failureCode: String? = null
        var saveSuccessSucceeds: Boolean = true

        /** [saveFailure] 를 실패(0행)로 흉내내려면 끈다 — 리뷰 HIGH-1 이중 정산 가드용. */
        var saveFailureSucceeds: Boolean = true
        val successWrites = mutableListOf<ConversionSuccessWrite>()
        val depthWhenMarked = mutableListOf<Int>()

        /**
         * `conversions.credits_reserved` 대역 — [settleCreditsReserved] 가 CAS 로
         * `0` 으로 낮춘다. [loadForProcessing] 은 이 값을 그대로 돌려줘 두 번째 정산
         * 시도가 "이미 0" 임을 보게 한다(리뷰 HIGH-1 멱등성 테스트).
         */
        private var creditsReservedColumn: Int = creditsReserved
        val settleAttempts = mutableListOf<Int>()

        override fun loadForProcessing(conversionId: UUID): ConversionWorkItem =
            ConversionWorkItem(
                conversionId = this.conversionId,
                documentId = documentId,
                status = status,
                sourceText = sourceText,
                workspaceId = workspaceId,
                userId = userId,
                // 원장 스냅샷(documentCharCount)이 요구하는 값 — 이 테스트는 그 값을 재지
                // 않으므로 고정값이면 충분하다.
                charCount = FAKE_DOCUMENT_CHAR_COUNT,
                creditsReserved = creditsReservedColumn,
            )

        override fun markProcessing(conversionId: UUID): Boolean {
            depthWhenMarked += transaction.depth
            status = ConversionStatus.PROCESSING
            return true
        }

        override fun saveSuccess(
            conversionId: UUID,
            write: ConversionSuccessWrite,
        ): Boolean {
            if (!saveSuccessSucceeds) return false
            successWrites += write
            status = ConversionStatus.DONE
            return true
        }

        override fun saveFailure(
            conversionId: UUID,
            failureCode: String,
            usage: ConversionUsage,
            attribution: LlmAttribution,
        ): Boolean {
            if (!saveFailureSucceeds) return false
            this.failureCode = failureCode
            status = ConversionStatus.FAILED
            return true
        }

        override fun settleCreditsReserved(
            conversionId: UUID,
            expectedAmount: Int,
        ): Boolean {
            settleAttempts += expectedAmount
            if (creditsReservedColumn != expectedAmount) return false
            creditsReservedColumn = 0
            return true
        }

        override fun revertToPending(conversionId: UUID): Boolean {
            status = ConversionStatus.PENDING
            return true
        }
    }

    private class SpyingProvider(private val delegate: FakeLlmProvider) : LlmProvider {
        override val name: String = delegate.name
        val calls get() = delegate.calls
        var onComplete: () -> Unit = {}

        override fun complete(
            prompt: LlmPrompt,
            options: LlmOptions,
        ) = onComplete().let { delegate.complete(prompt, options) }
    }

    private class RenewingHeartbeat(private val leases: FakeLeases) : ConversionJobHeartbeat {
        val renewed get() = leases.renewed

        override fun <T> whileHeld(
            lease: ConversionJobLease,
            block: () -> T,
        ): T {
            leases.renew(lease, Duration.ofSeconds(1))
            return block()
        }
    }

    private companion object {
        const val OWNER: String = "worker-a"

        /** `ConversionWorkItem.charCount`(원장 스냅샷 출처) — 이 테스트가 재지 않는 고정값. */
        const val FAKE_DOCUMENT_CHAR_COUNT: Int = 1000

        /** `ConversionWorkItem.creditsReserved` 기본값 — 소비·해제 검증이 재는 고정값. */
        const val FAKE_CREDITS_RESERVED: Int = 3
    }
}
