package kr.easydoc.application.conversion

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.ConversionRepository
import kr.easydoc.application.document.DocumentRepository
import kr.easydoc.application.document.ReconversionReservation
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.ExternalServiceUnavailableException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.ReconversionBudgetExhaustedException
import kr.easydoc.core.privacy.CONTENT_MASK
import kr.easydoc.core.privacy.maskText
import kr.easydoc.core.segment.maskedUnitOf
import kr.easydoc.core.segment.splitUnits
import java.util.UUID

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
 * **입력은 「문서 전체를 마스킹한 뒤의 n번째 줄」이다** — 단위만 따로 마스킹하면 자리표시자
 * 번호가 저장된 대응표와 어긋난다(`maskedUnitOf` KDoc). `ConvertDocumentUseCase.convertMasked`
 * 가 그 마스킹된 단위를 받아 1차 변환·조건부 보정·채택 판정까지 **자동 변환과 완전히 같은
 * 경로**를 탄다 — 최대 1회 변환 + 1회 조건부 보정.
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
) {
    /**
     * 판정 갈래마다 다른 HTTP 상태(404·409·422·429·502)로 나가는 독립 가드라 `ThrowsCount`
     * 를 억제한다 — 갈래를 줄이면 오히려 서로 다른 사용자 조치를 하나로 뭉갠다
     * (`GlobalExceptionHandler.mappingFor` 와 같은 판단).
     */
    @Suppress("ThrowsCount")
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
        val fullMasking = maskText(sourceText.value)
        val maskedUnitCount = splitUnits(fullMasking.maskedText.value).size
        if (sourceUnitIndex !in 0 until maskedUnitCount) {
            throw InvalidInputException(OUT_OF_RANGE_MESSAGE)
        }

        // 예약(트랜잭션 1) — 커밋하고 나간다. 실패면 LLM 호출 0회로 429.
        val reservation =
            transaction.inTransaction {
                conversions.reserveReconversionCalls(ownerId, conversionId, RECONVERSION_CALL_COST, callBudget)
            }
        if (reservation is ReconversionReservation.Exhausted) {
            throw ReconversionBudgetExhaustedException(BUDGET_EXHAUSTED_MESSAGE, reservation.remainingCallBudget)
        }

        // 외부 호출은 트랜잭션 밖이다 — 장시간 LLM 호출을 DB 트랜잭션 안에서 돌리지 않는다.
        val unitMasking = maskedUnitOf(fullMasking, sourceUnitIndex)
        val result = convert.convertMasked(unitMasking)

        return when (result) {
            is ConversionResult.Failed -> {
                // 첫 호출 자체가 실패했다 — 쓸 수 있는 후보가 없으므로 전액 환불한다.
                settle(ownerId, conversionId, actualUsed = 0)
                throw ExternalServiceUnavailableException(PROVIDER_UNREACHABLE_MESSAGE)
            }

            is ConversionResult.Converted -> {
                val remaining = settle(ownerId, conversionId, actualUsed = result.usage.llmCalls)
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
    }

    /** 정산(트랜잭션 2). */
    private fun settle(
        ownerId: UUID,
        conversionId: UUID,
        actualUsed: Int,
    ): Int =
        transaction.inTransaction {
            conversions.settleReconversionCalls(
                ownerId = ownerId,
                conversionId = conversionId,
                reservedAmount = RECONVERSION_CALL_COST,
                actualUsed = actualUsed,
                budget = callBudget,
            )
        }

    private companion object {
        /** 재변환 1회가 예약하는 호출 수 — 1차 변환 + 조건부 보정, 언제나 2(계획 §4 결정 3). */
        const val RECONVERSION_CALL_COST = 2

        const val CONVERSION_NOT_FOUND_MESSAGE = "변환 결과를 찾을 수 없습니다"
        const val CONVERSION_NOT_DONE_MESSAGE = "변환이 끝난 뒤에 다시 변환할 수 있습니다"
        const val OUT_OF_RANGE_MESSAGE = "원본 단위 색인이 범위를 벗어났습니다"
        const val BUDGET_EXHAUSTED_MESSAGE = "재변환 호출 예산을 모두 사용했습니다"
        const val PROVIDER_UNREACHABLE_MESSAGE = "요청을 처리하지 못했습니다"
    }
}
