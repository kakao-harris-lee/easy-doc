package kr.easydoc.application.conversion

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.MaskedItemWriter
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.StorageException
import org.slf4j.LoggerFactory

/**
 * 큐에서 작업 한 건을 집어 마스킹 → LLM → 결과 저장까지 실행한다.
 *
 * LLM 호출은 **트랜잭션 밖**이다. 장시간 외부 호출이 행 잠금을 붙잡고 있으면 삭제·검수·
 * 다른 worker 의 회수가 함께 멈춘다.
 */
class ProcessConversionJob(
    private val stores: ConversionWorkerStores,
    private val convert: ConvertDocumentUseCase,
    private val transaction: TransactionRunner,
    private val runtime: ConversionWorkerRuntime,
) {
    private val log = LoggerFactory.getLogger(ProcessConversionJob::class.java)

    /** 작업이 있으면 한 건 처리한다. */
    fun processNext(): ConversionJobOutcome =
        when (val claimed = transaction.inTransaction { claim() }) {
            ConversionAcquire.Empty -> {
                ConversionJobOutcome.IDLE
            }

            is ConversionAcquire.Exhausted -> {
                ConversionJobOutcome.FAILED
            }

            is ConversionAcquire.Held -> {
                prepare(claimed.lease)?.let { prepared -> runPrepared(claimed.lease, prepared) }
                    ?: ConversionJobOutcome.DROPPED
            }
        }

    /**
     * 리스를 집는다. 시도 상한을 넘긴 만료 작업은 같은 트랜잭션에서 변환 행도 실패로 맞춘다 —
     * 큐만 실패하고 변환이 `processing` 에 남으면 사용자는 영원히 기다린다.
     */
    private fun claim(): ConversionAcquire {
        val acquired =
            stores.leases.acquire(runtime.policy.owner, runtime.policy.leaseDuration, runtime.policy.maxAttempts)
        if (acquired is ConversionAcquire.Exhausted) {
            stores.work.saveFailure(
                acquired.conversionId,
                ATTEMPTS_EXHAUSTED_FAILURE_CODE,
                ConversionUsage(llmCalls = 0, inputTokens = 0, outputTokens = 0),
                LlmAttribution(convert.providerName, model = null),
            )
            log.info("시도 상한을 넘겨 변환을 실패로 확정한다: conversionId={}", acquired.conversionId)
        }
        return acquired
    }

    private fun runPrepared(
        lease: ConversionJobLease,
        prepared: ConversionWorkItem,
    ): ConversionJobOutcome =
        try {
            persist(lease, convertHeld(lease, prepared))
        } catch (exc: StorageException) {
            failPermanently(lease, exc::class.java.simpleName)
        } catch (exc: ConfigurationException) {
            failPermanently(lease, exc::class.java.simpleName)
        }

    /** 원문을 읽고 변환 상태를 `processing` 으로 올린다. LLM 전이다. */
    private fun prepare(lease: ConversionJobLease): ConversionWorkItem? =
        transaction.inTransaction {
            val item = stores.work.loadForProcessing(lease.conversionId)
            when {
                item == null -> {
                    stores.leases.complete(lease)
                    log.info("변환 작업 대상 행이 없다: conversionId={}", lease.conversionId)
                    null
                }

                item.status.exposesResult || item.status == ConversionStatus.FAILED -> {
                    stores.leases.complete(lease)
                    null
                }

                !stores.work.markProcessing(lease.conversionId) -> {
                    stores.leases.complete(lease)
                    null
                }

                else -> {
                    item
                }
            }
        }

    /**
     * 복호화와 LLM 은 트랜잭션 밖. [ConversionJobHeartbeat] 가 그 동안 리스를 연장한다.
     */
    private fun convertHeld(
        lease: ConversionJobLease,
        item: ConversionWorkItem,
    ): ConversionResult {
        val source = stores.cipher.decrypt(item.sourceText, item.documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)
        return runtime.heartbeat.whileHeld(lease) { convert.convert(source.value) }
    }

    private fun persist(
        lease: ConversionJobLease,
        result: ConversionResult,
    ): ConversionJobOutcome =
        when (result) {
            is ConversionResult.Converted -> {
                finishSuccess(lease, result)
            }

            is ConversionResult.Failed -> {
                failOrRetry(
                    lease,
                    PendingFailure(
                        failureCode = result.kind.failureCode,
                        retryable = result.kind.retryable,
                        usage = result.usage,
                        attribution = result.attribution,
                    ),
                )
            }
        }

    private fun finishSuccess(
        lease: ConversionJobLease,
        result: ConversionResult.Converted,
    ): ConversionJobOutcome {
        val easyText =
            stores.cipher.encrypt(
                PlainBody(result.easyText.value),
                lease.conversionId,
                EncryptedField.CONVERSION_EASY_TEXT,
            )
        val table =
            stores.cipher.encrypt(
                stores.maskedItems.encode(result.maskedItems),
                lease.conversionId,
                EncryptedField.CONVERSION_MASKED_ITEMS,
            )
        val saved =
            transaction.inTransaction {
                if (!stores.leases.lockIfHeld(lease)) return@inTransaction false
                val wrote =
                    stores.work.saveSuccess(
                        lease.conversionId,
                        ConversionSuccessWrite(
                            easyText = easyText,
                            maskedItems = table,
                            missingPlaceholders = result.missingPlaceholders,
                            attribution = result.attribution,
                            usage = result.usage,
                        ),
                    )
                stores.leases.complete(lease)
                wrote
            }
        return if (saved) ConversionJobOutcome.COMPLETED else ConversionJobOutcome.DROPPED
    }

    private fun failPermanently(
        lease: ConversionJobLease,
        failureCode: String,
    ): ConversionJobOutcome =
        failOrRetry(
            lease,
            PendingFailure(
                failureCode = failureCode,
                retryable = false,
                usage = ConversionUsage(llmCalls = 0, inputTokens = 0, outputTokens = 0),
                attribution = LlmAttribution(convert.providerName, model = null),
            ),
        )

    private fun failOrRetry(
        lease: ConversionJobLease,
        failure: PendingFailure,
    ): ConversionJobOutcome {
        val canRetry = failure.retryable && lease.attempts < runtime.policy.maxAttempts
        return transaction.inTransaction {
            if (!stores.leases.lockIfHeld(lease)) return@inTransaction ConversionJobOutcome.DROPPED
            if (canRetry) {
                stores.work.revertToPending(lease.conversionId)
                stores.leases.retry(lease, runtime.policy.retryBackoff)
                ConversionJobOutcome.RETRY_SCHEDULED
            } else {
                stores.work.saveFailure(lease.conversionId, failure.failureCode, failure.usage, failure.attribution)
                stores.leases.fail(lease)
                ConversionJobOutcome.FAILED
            }
        }
    }

    private class PendingFailure(
        val failureCode: String,
        val retryable: Boolean,
        val usage: ConversionUsage,
        val attribution: LlmAttribution,
    )

    companion object {
        /** 프로세스 중단으로 시도만 쌓인 작업. 계약 `failure_code` 예외 클래스명 형식. */
        const val ATTEMPTS_EXHAUSTED_FAILURE_CODE: String = "ConversionAttemptsExhaustedException"
    }
}

/** 하트비트와 재시도 정책. */
class ConversionWorkerRuntime(
    val heartbeat: ConversionJobHeartbeat,
    val policy: ConversionWorkerPolicy,
)

/** 저장소·암호·대응표 포트. 생성자 인자 수를 한 자리로 묶는다. */
class ConversionWorkerStores(
    val leases: ConversionJobLeasePort,
    val work: ConversionWorkStore,
    val cipher: ContentCipher,
    val maskedItems: MaskedItemWriter,
)
