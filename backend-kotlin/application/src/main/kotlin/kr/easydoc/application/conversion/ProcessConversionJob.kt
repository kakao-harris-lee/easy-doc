package kr.easydoc.application.conversion

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.exceptions.ConfigurationException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.segment.SourceStructure
import kr.easydoc.core.segment.splitUnits
import kr.easydoc.core.text.normalizeLineEndings
import org.slf4j.LoggerFactory

/**
 * 큐에서 작업 한 건을 집어 LLM → 결과 저장까지 실행한다.
 *
 * LLM 호출은 **트랜잭션 밖**이다. 장시간 외부 호출이 행 잠금을 붙잡고 있으면 삭제·검수·
 * 다른 worker 의 회수가 함께 멈춘다.
 *
 * `TooManyFunctions` 를 억제한다 — 크레딧 계정 정산(`consumeCredits`·`releaseCredits`)이
 * 더한 작은 헬퍼 둘이 늘렸을 뿐, 책임이 여럿으로 갈라진 것이 아니다.
 */
@Suppress("LongParameterList", "TooManyFunctions")
class ProcessConversionJob(
    private val stores: ConversionWorkerStores,
    private val convert: ConvertDocumentUseCase,
    private val transaction: TransactionRunner,
    private val runtime: ConversionWorkerRuntime,
    private val notifier: ConversionCompletedNotifier,
    /**
     * LLM 호출 원장(U1). **필수다** — 조립 지점(`DocumentConfiguration.llmCallLedger`)이
     * 실제 구현([kr.easydoc.infrastructure.document.JdbcLlmCallLedger])을 항상 넣는다.
     * production 조립에서 원장을 빠뜨리는 실수를 컴파일 시점에 막으려고 기본값을 두지
     * 않는다 — 테스트는 각자 no-op 대역을 명시한다.
     */
    private val ledger: LlmCallLedger,
    /**
     * 크레딧 계정(C1). `finishSuccess` 는 이 안에서 소비를 확정하고, 영구 실패 갈래는
     * 예약을 해제한다 — 재시도 예정 실패는 손대지 않는다(크레딧 계정 계획 §2 결정 5).
     */
    private val creditAccountService: CreditAccountService,
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
     *
     * **크레딧 예약도 여기서 해제한다** — 이 갈래는 [ConversionWorkItem] 을 아직 읽지 않은
     * 채로 영구 실패가 확정되므로, 해제에 필요한 소유 문맥(워크스페이스·소유자·예약분)을
     * [stores.work.loadForProcessing] 으로 별도로 읽는다. 행이 이미 사라졌으면(동시 삭제)
     * 해제할 예약도 없다 — 조용히 넘어간다.
     */
    private fun claim(): ConversionAcquire {
        val acquired =
            stores.leases.acquire(runtime.policy.owner, runtime.policy.leaseDuration, runtime.policy.maxAttempts)
        if (acquired is ConversionAcquire.Exhausted) {
            val item = stores.work.loadForProcessing(acquired.conversionId)
            val failed =
                stores.work.saveFailure(
                    acquired.conversionId,
                    ATTEMPTS_EXHAUSTED_FAILURE_CODE,
                    ConversionUsage(llmCalls = 0, inputTokens = 0, outputTokens = 0),
                    LlmAttribution(convert.providerName, model = null),
                )
            // saveFailure 가 0행이면(이미 다른 경로가 끝냈다) 예약도 그 경로가 처리했거나
            // 처리할 몫이다 — 여기서 또 해제하면 이중 정산이 된다(리뷰 HIGH-1).
            if (failed) {
                item?.let { releaseCredits(it) }
            }
            log.info("시도 상한을 넘겨 변환을 실패로 확정한다: conversionId={}", acquired.conversionId)
        }
        return acquired
    }

    private fun runPrepared(
        lease: ConversionJobLease,
        prepared: ConversionWorkItem,
    ): ConversionJobOutcome =
        try {
            persist(lease, prepared, convertHeld(lease, prepared))
        } catch (exc: StorageException) {
            failPermanently(lease, exc::class.java.simpleName, prepared)
        } catch (exc: ConfigurationException) {
            failPermanently(lease, exc::class.java.simpleName, prepared)
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
        // item.structure 가 null 이면(옛 문서, 계획 §1.2) 전부 BODY 로 접는다 —
        // ConvertDocumentUseCase.convert 의 기본값과 같은 방침이다.
        val structure = item.structure ?: SourceStructure.allBody(splitUnits(source.value).size)
        return runtime.heartbeat.whileHeld(lease) { convert.convert(source.value, structure = structure) }
    }

    private fun persist(
        lease: ConversionJobLease,
        item: ConversionWorkItem,
        result: ConversionResult,
    ): ConversionJobOutcome =
        when (result) {
            is ConversionResult.Converted -> {
                finishSuccess(lease, item, result)
            }

            is ConversionResult.Failed -> {
                failOrRetry(
                    lease,
                    PendingFailure(
                        failureCode = result.kind.failureCode,
                        retryable = result.kind.retryable,
                        usage = result.usage,
                        attribution = result.attribution,
                        entries = ledgerEntriesOf(item, result.usage),
                    ),
                    item,
                )
            }
        }

    /**
     * [usage] 의 호출 기록을 [item] 의 소유 문맥(원장 [LlmCallEntry])으로 바꾼다. 비어 있으면
     * 빈 목록 — [LlmCallLedger.append] 가 빈 목록을 아무것도 쓰지 않는 것으로 정의한다.
     *
     * **`calledAt` 을 여기서 다시 재지 않는다** — 각 [LlmCallRecord.calledAt] 이 이미
     * `ConvertDocumentUseCase.Pass.complete` 에서 호출 직후 캡처한 값이다. 여기서 시계를
     * 새로 읽으면(예: 한 값을 배치 전체에 공유) 저장 시각이 찍히고, 두 호출(1차·보정)의
     * 실제 호출 간격도 사라진다.
     */
    private fun ledgerEntriesOf(
        item: ConversionWorkItem,
        usage: ConversionUsage,
    ): List<LlmCallEntry> =
        usage.calls.map { record ->
            LlmCallEntry(
                conversionId = item.conversionId,
                documentId = item.documentId,
                workspaceId = item.workspaceId,
                userId = item.userId,
                record = record,
                calledAt = record.calledAt,
                documentCharCount = item.charCount,
            )
        }

    private fun finishSuccess(
        lease: ConversionJobLease,
        item: ConversionWorkItem,
        result: ConversionResult.Converted,
    ): ConversionJobOutcome {
        // 저장 경계에서 개행을 통일한다 — `DocumentService.store`·`ConversionReviewService.save`
        // 와 같은 이유(§ 두 KDoc)다: `core.segment.splitUnits` 가 `\n` 만으로 줄을 가르므로,
        // LLM 이 CRLF 를 섞어 낸 채로 저장되면(어댑터가 보장하지 않는다) `segment_map`·문체
        // 판정·재변환이 원문과 어긋난 줄 수를 본다. `easyText` 는 1차 결과와 보정 채택 결과를
        // 이미 하나로 접은 최종 본문이라 이 자리 하나가 이 경로의 유일한 저장 지점이다
        // (`ConvertDocumentUseCase.Pass.finish` 의 `ConversionResult.Converted.easyText`).
        val easyText =
            stores.cipher.encrypt(
                PlainBody(normalizeLineEndings(result.easyText.value)),
                lease.conversionId,
                EncryptedField.CONVERSION_EASY_TEXT,
            )
        val saved =
            transaction.inTransaction {
                if (!stores.leases.lockIfHeld(lease)) return@inTransaction false
                val wrote =
                    stores.work.saveSuccess(
                        lease.conversionId,
                        ConversionSuccessWrite(
                            easyText = easyText,
                            attribution = result.attribution,
                            usage = result.usage,
                        ),
                    )
                // 완료 저장과 **같은 트랜잭션**에서 원장을 쓴다(계획 §2 결정 2, §6 리스크 2) —
                // 원장 쓰기 실패가 곧 이 완료 저장의 롤백이다.
                if (wrote) {
                    ledger.append(ledgerEntriesOf(item, result.usage))
                    // 크레딧 계정 계획 §2 결정 5 — 완료 저장과 같은 트랜잭션에서 예약을
                    // 소비로 확정한다. `credits_reserved` 가 0 이면(V15 이전 문서)
                    // `CreditAccountService.consume` 이 no-op 이다.
                    consumeCredits(item)
                }
                stores.leases.complete(lease)
                wrote
            }
        // 알림은 커밋 **뒤**, 트랜잭션 밖에서 부른다 — 메일 발송은 외부 호출이라 DB 트랜잭션
        // 안에 두지 않는다(CLAUDE.md). 실패해도 이 완료 결과를 되돌리지 않는다 — 내부에서
        // 예외를 삼킨다(ConversionCompletedNotifier KDoc).
        if (saved) {
            notifier.notify(lease.conversionId)
        }
        return if (saved) ConversionJobOutcome.COMPLETED else ConversionJobOutcome.DROPPED
    }

    private fun failPermanently(
        lease: ConversionJobLease,
        failureCode: String,
        item: ConversionWorkItem,
    ): ConversionJobOutcome =
        failOrRetry(
            lease,
            PendingFailure(
                failureCode = failureCode,
                retryable = false,
                usage = ConversionUsage(llmCalls = 0, inputTokens = 0, outputTokens = 0),
                attribution = LlmAttribution(convert.providerName, model = null),
            ),
            item,
        )

    private fun failOrRetry(
        lease: ConversionJobLease,
        failure: PendingFailure,
        item: ConversionWorkItem,
    ): ConversionJobOutcome {
        val canRetry = failure.retryable && lease.attempts < runtime.policy.maxAttempts
        return transaction.inTransaction {
            if (!stores.leases.lockIfHeld(lease)) return@inTransaction ConversionJobOutcome.DROPPED
            // 원장은 재시도 여부와 무관하게, **이 트랜잭션에서 항상** 쓴다. `PROVIDER_ERROR`
            // 는 재시도 대상(retryable)이지만 REFUSAL 처럼 완성 응답을 실제로 받아 토큰을
            // 쓴 채로 이 종류가 되는 경우가 있다(`ConvertDocumentUseCase` 의 `classify` —
            // REFUSAL 도 PROVIDER_ERROR 로 분류된다) — 그 호출은 이미 벌어졌고 재시도해도
            // 이번 시도의 청구 근거가 없어지지 않는다. **`LlmProviderException`(완성
            // 자체가 나지 않은 경우)도 이제 항목을 남긴다** — `outcome = provider_error`,
            // 토큰 0(백로그 「실패 호출 원장 추적」, 2026-09-08) — 벤더가 실패한 요청의
            // 입력 토큰에 과금할 수 있어 그 행이 없으면 대조할 방법이 없었다.
            // [failure.entries] 가 비어 있는 것은 LLM 을 아예 부르지 못한 경로
            // (`ConversionAcquire.Exhausted`처럼 `convert.convert` 자체를 안 부른 경우)뿐이다.
            ledger.append(failure.entries)
            if (canRetry) {
                // 재시도 예정 — 크레딧 예약에 손대지 않는다(계획 §2 결정 5). 문서는 아직
                // 청구도 환불도 확정되지 않은 채로 다시 시도된다.
                stores.work.revertToPending(lease.conversionId)
                stores.leases.retry(lease, runtime.policy.retryBackoff)
                ConversionJobOutcome.RETRY_SCHEDULED
            } else {
                val failed =
                    stores.work.saveFailure(lease.conversionId, failure.failureCode, failure.usage, failure.attribution)
                // 영구 실패 — 변환이 안 된 문서는 청구하지 않는다. 같은 트랜잭션에서 예약을
                // 해제한다(계획 §2 결정 5). saveFailure 가 0행이면(다른 경로가 이미 끝냈다)
                // 해제도 건너뛴다 — 이중 정산 방지(리뷰 HIGH-1).
                if (failed) {
                    releaseCredits(item)
                }
                stores.leases.fail(lease)
                ConversionJobOutcome.FAILED
            }
        }
    }

    /**
     * [stores.work.settleCreditsReserved] 로 먼저 CAS 를 걸고, 그때만
     * [CreditAccountService.consume] 을 부른다(리뷰 HIGH-1) — 리스 만료 뒤 다른 worker 가
     * 같은 완료를 또 처리하거나, `DocumentService.delete` 가 그새 예약을 해제했으면 정산은
     * 이미 끝난 것이라 여기서 또 소비하면 계정을 두 번 건드린다.
     */
    private fun consumeCredits(item: ConversionWorkItem) {
        if (!stores.work.settleCreditsReserved(item.conversionId, item.creditsReserved)) {
            return
        }
        creditAccountService.consume(
            workspaceId = item.workspaceId,
            ownerId = item.userId,
            documentId = item.documentId,
            conversionId = item.conversionId,
            amount = Credits(item.creditsReserved),
        )
    }

    /** [consumeCredits] 와 같은 CAS 가드 — 해제도 한 번만. */
    private fun releaseCredits(item: ConversionWorkItem) {
        if (!stores.work.settleCreditsReserved(item.conversionId, item.creditsReserved)) {
            return
        }
        creditAccountService.release(
            workspaceId = item.workspaceId,
            ownerId = item.userId,
            documentId = item.documentId,
            conversionId = item.conversionId,
            amount = Credits(item.creditsReserved),
        )
    }

    private class PendingFailure(
        val failureCode: String,
        val retryable: Boolean,
        val usage: ConversionUsage,
        val attribution: LlmAttribution,
        val entries: List<LlmCallEntry> = emptyList(),
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

/** 저장소·암호 포트. 생성자 인자 수를 한 자리로 묶는다. */
class ConversionWorkerStores(
    val leases: ConversionJobLeasePort,
    val work: ConversionWorkStore,
    val cipher: ContentCipher,
)
