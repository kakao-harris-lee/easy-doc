package kr.easydoc.application.conversion

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.MaskedItemWriter
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.EncryptionScheme
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.crypto.PlainBytes
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.exceptions.LlmProviderException
import kr.easydoc.core.llm.FakeLlmProvider
import kr.easydoc.core.llm.FakeLlmTurn
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
        @DisplayName("완료 결과의 본문·대응표는 변환 행 식별자로 봉인한다")
        fun `결과 결속은 변환 식별자다`() {
            val world = World()

            world.jobs.processNext()

            val fields = world.cipher.sealed.map { it.third }
            assertThat(fields).contains(EncryptedField.CONVERSION_EASY_TEXT, EncryptedField.CONVERSION_MASKED_ITEMS)
            assertThat(world.cipher.sealed.filter { it.third != EncryptedField.DOCUMENT_SOURCE_TEXT })
                .allMatch { it.second == world.conversionId }
        }
    }

    @Nested
    @DisplayName("마스킹 선행")
    inner class MaskingFirst {
        @Test
        @DisplayName("원문 개인정보는 LlmProvider 프롬프트에 실리지 않는다")
        fun `마스킹된 본문만 나간다`() {
            val world = World(source = "신청자 900101-1234567 님께 안내합니다.")

            world.jobs.processNext()

            assertThat(world.provider.calls).isNotEmpty()
            world.provider.calls.forEach { call ->
                assertThat(call.prompt.user).doesNotContain("900101-1234567")
                assertThat(call.prompt.system).doesNotContain("900101-1234567")
                assertThat(call.prompt.user).contains("[[주민등록번호1]]")
            }
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

    private class World(
        source: String = "복지 급여를 안내합니다.",
        provider: FakeLlmProvider = FakeLlmProvider.replying("오늘 서류를 내세요."),
        lease: ConversionJobLease? = ConversionJobLease(UUID.randomUUID(), OWNER, attempts = 1),
        attempts: Int = 1,
        exhausted: Boolean = false,
    ) {
        val conversionId: UUID = lease?.conversionId ?: UUID.randomUUID()
        val documentId: UUID = UUID.randomUUID()
        val lease: ConversionJobLease? =
            lease?.let { ConversionJobLease(conversionId, it.owner, attempts) }
        val transaction = RecordingDepth()
        val cipher = RecordingCipher(transaction)
        val sourceSealed = cipher.encrypt(PlainBody(source), documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)
        val leases = FakeLeases(this.lease, transaction, exhausted)
        val work = FakeWork(conversionId, documentId, sourceSealed, transaction)
        val provider = SpyingProvider(provider)
        val heartbeat = RenewingHeartbeat(leases)
        val jobs =
            ProcessConversionJob(
                stores =
                    ConversionWorkerStores(
                        leases = leases,
                        work = work,
                        cipher = cipher,
                        maskedItems = MaskedItemWriter { items -> PlainBody(items.joinToString { it.placeholder }) },
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
            )
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

    private class FakeWork(
        private val conversionId: UUID,
        private val documentId: UUID,
        private val sourceText: EncryptedContent,
        private val transaction: RecordingDepth,
    ) : ConversionWorkStore {
        var status: ConversionStatus = ConversionStatus.PENDING
        var failureCode: String? = null
        var saveSuccessSucceeds: Boolean = true
        val successWrites = mutableListOf<ConversionSuccessWrite>()
        val depthWhenMarked = mutableListOf<Int>()

        override fun loadForProcessing(conversionId: UUID): ConversionWorkItem =
            ConversionWorkItem(
                conversionId = this.conversionId,
                documentId = documentId,
                status = status,
                sourceText = sourceText,
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
            this.failureCode = failureCode
            status = ConversionStatus.FAILED
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
    }
}
