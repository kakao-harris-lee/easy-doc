package kr.easydoc.application.conversion

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.credit.CreditAccountService
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.application.document.ReconversionReservation
import kr.easydoc.application.document.SegmentMapDerivation
import kr.easydoc.core.credit.Credits
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.ReconversionBudgetExhaustedException
import kr.easydoc.core.exceptions.ReconversionConcurrencyExhaustedException
import kr.easydoc.core.llm.LlmCallPurpose
import kr.easydoc.core.llm.LlmCallRecord
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.segment.SegmentConfidence
import kr.easydoc.core.segment.SourceStructure
import kr.easydoc.core.segment.splitUnits
import java.util.UUID
import java.util.concurrent.Semaphore

/** [ReconvertUnitService.reconvert] 의 결과 — **후보 텍스트뿐이고 아무것도 저장하지 않는다.** */
data class ReconvertUnitResult(
    val sourceUnitIndex: Int,
    val easyUnitIndexes: List<Int>,
    val easyTextFingerprint: String,
    val candidateText: String,
    val llmCallsUsed: Int,
    val remainingCallBudget: Int,
) {
    /** **후보 본문을 찍지 않는다** — 사용자 문서 내용이다. 지문은 해시값이라도 같은 규약을 따른다. */
    override fun toString(): String =
        "ReconvertUnitResult(sourceUnitIndex=$sourceUnitIndex, easyUnitIndexes=$easyUnitIndexes, " +
            "easyTextFingerprint=$CONTENT_MASK, candidateText=$CONTENT_MASK ${candidateText.length}자, " +
            "llmCallsUsed=$llmCallsUsed, remainingCallBudget=$remainingCallBudget)"
}

/**
 * 원본 단위 하나를 다시 변환한다(P0-4 S4, 계획 §4 결정 3).
 *
 * **동기 처리, 응답은 후보뿐이다** — 변환 본문에는 아무것도 쓰지 않는다. 채택은 클라이언트
 * 몫이고(자동 교체는 어떤 경우에도 없다), 서버는 후보를 만들어 돌려주는 것과 예산을
 * 지키는 것만 한다.
 *
 * **입력은 「원본 문서의 n번째 줄」이다** — `splitUnits(sourceText)[sourceUnitIndex]`.
 * `ConvertDocumentUseCase.convert` 가 그 단위를 받아 1차 변환·조건부 보정·채택 판정까지
 * **자동 변환과 완전히 같은 경로**를 탄다 — 최대 1회 변환 + 1회 조건부 보정.
 *
 * **예산은 요청이 아니라 LLM 호출 수로 센다.** 호출 전 [ConversionRepository.reserveReconversionCalls]
 * 로 2회를 예약하고(트랜잭션 1), 호출 뒤 실제 사용량만 남기고
 * [ConversionRepository.settleReconversionCalls] 로 환불한다(트랜잭션 2) — 실제 LLM 호출은
 * 두 트랜잭션 **밖**에서 돈다(장시간 외부 호출을 DB 트랜잭션 안에서 하지 않는다).
 */
@Suppress("LongParameterList")
class ReconvertUnitService(
    private val conversions: ConversionRepository,
    private val documents: DocumentRepository,
    private val cipher: ContentCipher,
    private val convert: ConvertDocumentUseCase,
    private val transaction: TransactionRunner,
    /** `easydoc.reconversion.call-budget` — 문서 1건당 재변환 LLM 호출 예산(계획 §0 게이트 1, 기본 20). */
    private val callBudget: Int,
    /**
     * `easydoc.reconversion.concurrency` — 이 프로세스가 동시에 진행할 수 있는 재변환 LLM
     * 호출 수의 상한(기본 4). [callBudget]은 문서 1건당 영구 호출 상한이고, 이쪽은 프로세스
     * 전역의 동시 in-flight 상한이다 — 제공자 과부하를 막는 bulkhead(코드 리뷰 item 2).
     */
    private val concurrencyLimit: Int,
    /**
     * LLM 호출 원장(U1). **필수다** — 조립 지점(`DocumentConfiguration.llmCallLedger`)이 실제
     * 구현을 항상 넣는다(`ProcessConversionJob` 과 같은 판단). 테스트는 각자 no-op 대역을
     * 명시한다.
     */
    private val ledger: LlmCallLedger,
    /** 재변환 대상 원문 단위도 월 제공 이용량에서 차감한다. */
    private val credits: CreditAccountService,
    /** 조회·내보내기와 같은 현재 본문 기반 segment_map 유도기. */
    private val segmentMapDerivation: SegmentMapDerivation,
) {
    /** [concurrencyLimit] 개의 허가를 두는 bulkhead — LLM 호출 구간만 감싼다. */
    private val reconversionGate = Semaphore(concurrencyLimit)

    /**
     * 판정 갈래마다 다른 HTTP 상태(404·409·422·429·502)로 나가는 독립 가드라 `ThrowsCount`
     * 를 억제한다 — 갈래를 줄이면 오히려 서로 다른 사용자 조치를 하나로 뭉갠다
     * (`GlobalExceptionHandler.mappingFor` 와 같은 판단).
     */
    @Suppress("ThrowsCount", "LongMethod")
    fun reconvert(
        ownerId: UUID,
        conversionId: UUID,
        sourceUnitIndex: Int,
        easyUnitIndexes: List<Int>,
        easyTextFingerprint: String,
    ): ReconvertUnitResult {
        val stored =
            conversions.findOwnedResult(ownerId, conversionId)
                ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
        if (stored.status != ConversionStatus.DONE) throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)

        val source =
            documents.findOwnedSource(ownerId, stored.documentId)
                ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
        val sourceText = cipher.decrypt(source.sourceText, source.documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)
        val sourceUnits = splitUnits(sourceText.value)
        if (sourceUnitIndex !in sourceUnits.indices) {
            throw InvalidInputException(OUT_OF_RANGE_MESSAGE)
        }
        // 형식만 본다 — 에디터 현재 본문과 실제로 일치하는지는 서버가 판정하지 않는다
        // (계획 §4 결정 3, 계약 `reconvertUnit` 설명). 예산에 닿기 전에 걸러 낸다.
        if (!FINGERPRINT_PATTERN.matches(easyTextFingerprint)) {
            throw InvalidInputException(INVALID_FINGERPRINT_MESSAGE)
        }

        // R3가 켜진 경우에만 저장된 현재 본문을 복호화해 앞선 쉬운 글 문맥을 만든다. 지도나
        // 본문이 없거나 대응이 모호하면 null로 접어 R3_UNIT의 보수적 경로를 탄다. 이 계산은
        // 기존 저장 경로 밖의 순수 유도이며 provider를 추가 호출하지 않는다.
        val priorBodyContext =
            if (convert.reconversionContextEnabled) {
                priorBodyContext(
                    stored = stored,
                    source = source,
                    sourceUnits = sourceUnits,
                    sourceUnitIndex = sourceUnitIndex,
                    requestedEasyUnitIndexes = easyUnitIndexes,
                )
            } else {
                null
            }

        val unit = sourceUnits[sourceUnitIndex]
        val requiredCredits = Credits.requiredFor(unit.length)

        // 예약(트랜잭션 1) — 대상 원문 분량의 크레딧과 최대 LLM 호출을 함께 잡는다.
        // 호출 예산이 없으면 같은 트랜잭션에서 크레딧 예약을 즉시 되돌리고 429로 끝낸다.
        val reservation =
            reserveCapacity(ownerId, source.workspaceId, stored.documentId, conversionId, requiredCredits)
        if (reservation is ReconversionReservation.Exhausted) {
            throw ReconversionBudgetExhaustedException(BUDGET_EXHAUSTED_MESSAGE, reservation.remainingCallBudget)
        }

        // 동시 in-flight 상한 — 여기서부터 LLM 호출 구간만 감싼다(리뷰 item 2 "LLM 구간만").
        if (!reconversionGate.tryAcquire()) {
            // 호출을 시작하지 못했다 — 예약 전액을 환불한다(트랜잭션 2), LLM 호출 0회.
            settle(
                ownerId,
                stored.documentId,
                source.workspaceId,
                conversionId,
                actualUsed = 0,
                documentCharCount = source.charCount,
                requiredCredits = requiredCredits,
                consumeCredits = false,
            )
            throw ReconversionConcurrencyExhaustedException(CONCURRENCY_LIMIT_MESSAGE)
        }
        // 외부 호출은 트랜잭션 밖이다 — 장시간 LLM 호출을 DB 트랜잭션 안에서 돌리지 않는다.
        // purpose = RECONVERT — 1차·보정 호출 둘 다 원장에 `reconvert` 로 남는다
        // (`ConvertDocumentUseCase.convert` KDoc 「purpose 매개변수」).
        // 대상 단위의 종류(표 칸·목록 항목)만 담은 크기 1짜리 구조를 넘긴다(계획 §1.3) — 이
        // 호출의 원문이 그 단위 하나뿐이라 splitUnits(unit).size 도 언제나 1이다.
        val targetKind = source.structureOrBody(sourceUnits.size).kinds[sourceUnitIndex]
        val unitStructure = SourceStructure(listOf(targetKind))
        val result =
            try {
                convert.convert(
                    unit,
                    structure = unitStructure,
                    purpose = LlmCallPurpose.RECONVERT,
                    priorBodyContext = priorBodyContext,
                )
            } finally {
                reconversionGate.release()
            }

        return finishResult(
            ownerId,
            stored.documentId,
            source.workspaceId,
            conversionId,
            sourceUnitIndex,
            easyUnitIndexes,
            easyTextFingerprint,
            result,
            documentCharCount = source.charCount,
            requiredCredits = requiredCredits,
        )
    }

    /**
     * 저장된 현재 본문에서 대상 단위보다 앞선 완전한 prefix만 돌려준다.
     *
     * `easyUnitIndexes`는 요청 당시 화면의 값일 수 있으므로 위치 계산의 기준으로 쓰지 않는다.
     * 대신 조회·내보내기와 같은 [SegmentMapDerivation]으로 현재 본문을 다시 대응한다. 대상과
     * 앞선 단위가 모두 HIGH이고 대상 단위가 정확히 하나의 원문 단위에 대응할 때만 prefix를
     * 제공한다. 대상 하나가 여러 쉬운 글 단위로 나뉜 경우에는 요청이 그 전체 매핑을 가리킬
     * 때 허용한다. 대상과 앞선 단위의 대응이 모호하거나 LOW·개수 불일치이면 첫 등장 여부를
     * 알 수 없으므로 null이다. 정확한 첫 단위 mapping은 앞선 본문이 없다는 검증 결과인
     * 빈 문자열을 반환한다 — unknown context(null)와 구분해야 전체 R3 정책을 쓸 수 있다.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition", "ReturnCount")
    private fun priorBodyContext(
        stored: kr.easydoc.application.document.StoredConversion,
        source: kr.easydoc.application.document.StoredSourceText,
        sourceUnits: List<String>,
        sourceUnitIndex: Int,
        requestedEasyUnitIndexes: List<Int>,
    ): String? {
        val edited = stored.ciphertexts.editedText
        val easy = stored.ciphertexts.easyText
        val encryptedBody = edited ?: easy ?: return null
        val bodyField =
            if (edited != null) {
                EncryptedField.CONVERSION_EDITED_TEXT
            } else {
                EncryptedField.CONVERSION_EASY_TEXT
            }
        val body = cipher.decrypt(encryptedBody, stored.id, bodyField)
        val easyUnits = splitUnits(body.value)
        val map = segmentMapDerivation.deriveOrNull(source, body) ?: return null
        if (
            map.sourceUnitCount != sourceUnits.size ||
            map.easyUnitCount != easyUnits.size ||
            map.units.size != easyUnits.size ||
            map.units.any { it.easyUnitIndex !in easyUnits.indices }
        ) {
            return null
        }

        val mappedToTarget = map.units.filter { sourceUnitIndex in it.sourceUnitIndexes }
        if (
            mappedToTarget.isEmpty() ||
            mappedToTarget.any { !isExactHighMapping(it, sourceUnitIndex) }
        ) {
            return null
        }
        val mappedIndexes = mappedToTarget.map { it.easyUnitIndex }.sorted()
        // An empty/stale request must not make the server guess which part of a merged mapping was
        // selected. The request may reorder indexes, but it must name the whole current mapping.
        if (requestedEasyUnitIndexes.distinct().sorted() != mappedIndexes) return null
        val targetEasyIndex = mappedIndexes.first()
        if (targetEasyIndex == 0) return ""

        val preceding = map.units.filter { it.easyUnitIndex < targetEasyIndex }
        if (
            preceding.any { it.confidence != SegmentConfidence.HIGH } ||
            preceding.any {
                it.sourceUnitIndexes.isEmpty() ||
                    it.sourceUnitIndexes.any { index -> index >= sourceUnitIndex }
            }
        ) {
            return null
        }
        val prefix = easyUnits.take(targetEasyIndex).joinToString("\n")
        return prefix.takeIf { it.length <= MAX_PRIOR_BODY_CONTEXT_CHARS }
    }

    private fun isExactHighMapping(
        unit: kr.easydoc.core.segment.SegmentUnit,
        sourceUnitIndex: Int,
    ): Boolean = unit.confidence == SegmentConfidence.HIGH && unit.sourceUnitIndexes == listOf(sourceUnitIndex)

    /** 크레딧과 호출 예산을 한 트랜잭션에서 예약한다. */
    private fun reserveCapacity(
        ownerId: UUID,
        workspaceId: UUID,
        documentId: UUID,
        conversionId: UUID,
        requiredCredits: Credits,
    ): ReconversionReservation =
        transaction.inTransaction {
            credits.reserve(ownerId, workspaceId, documentId, requiredCredits)
            conversions
                .reserveReconversionCalls(ownerId, conversionId, RECONVERSION_CALL_COST, callBudget)
                .also { reservedCalls ->
                    if (reservedCalls is ReconversionReservation.Exhausted) {
                        credits.release(workspaceId, ownerId, documentId, conversionId, requiredCredits)
                    }
                }
        }

    /** LLM 호출 뒤 정산하고 결과를 만든다 — [reconvert] 에서 갈라낸 자리(`LongMethod`). */
    @Suppress("LongParameterList")
    private fun finishResult(
        ownerId: UUID,
        documentId: UUID,
        workspaceId: UUID,
        conversionId: UUID,
        sourceUnitIndex: Int,
        easyUnitIndexes: List<Int>,
        easyTextFingerprint: String,
        result: ConversionResult,
        documentCharCount: Int,
        requiredCredits: Credits,
    ): ReconvertUnitResult =
        when (result) {
            is ConversionResult.Failed -> {
                // 첫 호출 자체가 실패했어도 `CompletionBudget.spend`는 호출 **전** spent 를
                // 올린다 — 시도 자체가 실제 사용량 1회다(제공자가 과금했을 수도 있다).
                // 그래서 0이 아니라 result.usage.llmCalls(=1)만큼만 환불에서 제외한다.
                settle(
                    ownerId,
                    documentId,
                    workspaceId,
                    conversionId,
                    actualUsed = result.usage.llmCalls,
                    calls = result.usage.calls,
                    documentCharCount = documentCharCount,
                    requiredCredits = requiredCredits,
                    consumeCredits = false,
                )
                throw ExternalServiceUnavailableException(PROVIDER_UNREACHABLE_MESSAGE)
            }

            is ConversionResult.Converted -> {
                val remaining =
                    settle(
                        ownerId,
                        documentId,
                        workspaceId,
                        conversionId,
                        actualUsed = result.usage.llmCalls,
                        calls = result.usage.calls,
                        documentCharCount = documentCharCount,
                        requiredCredits = requiredCredits,
                        consumeCredits = true,
                    )
                ReconvertUnitResult(
                    sourceUnitIndex = sourceUnitIndex,
                    easyUnitIndexes = easyUnitIndexes,
                    easyTextFingerprint = easyTextFingerprint,
                    candidateText = result.easyText.value,
                    llmCallsUsed = result.usage.llmCalls,
                    remainingCallBudget = remaining,
                )
            }
        }

    /**
     * 정산(트랜잭션 2) — 예산 환불과 **같은 트랜잭션**에서 원장([calls])도 함께 쓴다
     * (`ProcessConversionJob.finishSuccess` 와 같은 판단, 계획 §2 결정 2). [calls] 가 비어
     * 있으면(호출을 아예 시작하지 못한 경로) 원장에 아무것도 쓰지 않는다.
     *
     * **`calledAt` 을 여기서 다시 재지 않는다** — 각 [LlmCallRecord.calledAt] 이 이미
     * `ConvertDocumentUseCase.Pass.complete` 에서 호출 직후 캡처한 값이다(`ProcessConversionJob
     * .ledgerEntriesOf` 와 같은 이유).
     */
    @Suppress("LongParameterList")
    private fun settle(
        ownerId: UUID,
        documentId: UUID,
        workspaceId: UUID,
        conversionId: UUID,
        actualUsed: Int,
        documentCharCount: Int,
        calls: List<LlmCallRecord> = emptyList(),
        requiredCredits: Credits,
        consumeCredits: Boolean,
    ): Int =
        transaction.inTransaction {
            val remaining =
                conversions.settleReconversionCalls(
                    ownerId = ownerId,
                    conversionId = conversionId,
                    reservedAmount = RECONVERSION_CALL_COST,
                    actualUsed = actualUsed,
                    budget = callBudget,
                )
            if (calls.isNotEmpty()) {
                ledger.append(
                    calls.map { record ->
                        LlmCallEntry(
                            conversionId = conversionId,
                            documentId = documentId,
                            workspaceId = workspaceId,
                            userId = ownerId,
                            record = record,
                            calledAt = record.calledAt,
                            documentCharCount = documentCharCount,
                        )
                    },
                )
            }
            if (consumeCredits) {
                credits.consume(workspaceId, ownerId, documentId, conversionId, requiredCredits)
            } else {
                credits.release(workspaceId, ownerId, documentId, conversionId, requiredCredits)
            }
            remaining
        }

    private companion object {
        /** 재변환 1회가 예약하는 호출 수 — 1차 변환 + 조건부 보정, 언제나 2(계획 §4 결정 3). */
        const val RECONVERSION_CALL_COST = 2

        /** 계약이 못박은 `easy_text_fingerprint` 형식(SHA-256 hex) — API wire 불변식이라 상수다. */
        val FINGERPRINT_PATTERN = Regex("^[0-9a-f]{64}$")

        const val CONVERSION_NOT_FOUND_MESSAGE = "변환 결과를 찾을 수 없습니다"
        const val CONVERSION_NOT_DONE_MESSAGE = "변환이 끝난 뒤에 다시 변환할 수 있습니다"
        const val OUT_OF_RANGE_MESSAGE = "원본 단위 색인이 범위를 벗어났습니다"
        const val INVALID_FINGERPRINT_MESSAGE = "easy_text_fingerprint 형식이 올바르지 않습니다"
        const val BUDGET_EXHAUSTED_MESSAGE = "재변환 호출 예산을 모두 사용했습니다"
        const val PROVIDER_UNREACHABLE_MESSAGE = "요청을 처리하지 못했습니다"
        const val CONCURRENCY_LIMIT_MESSAGE = "동시 재변환 한도에 도달했습니다. 잠시 후 다시 시도해 주세요"

        /** 전체 앞부분을 보내지 못하면 일부 문맥으로 첫 등장을 추측하지 않는다. */
        const val MAX_PRIOR_BODY_CONTEXT_CHARS = 12_000
    }
}
