package kr.easydoc.application.illustration.suggestion

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.document.CONVERSION_NOT_FOUND_MESSAGE
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestion
import kr.easydoc.core.illustration.suggestion.IllustrationSuggestionStorageCodec
import java.math.BigDecimal
import java.util.UUID

/** 조회 시점에 정한 최신 결과의 상태(명세 §6). 트리거가 결과를 고치지 않고 읽을 때 비교한다. */
enum class IllustrationSuggestionResultStatus(val wireName: String) {
    /** 이 변환에 분석 결과가 아직 없다. */
    NOT_ANALYZED("not_analyzed"),

    /** 현재 본문 기준의 결과가 있고 제안이 1건 이상이다. */
    READY("ready"),

    /** 현재 본문 기준의 결과가 있고 제안이 0건이다 — 정상 결과이며 실패가 아니다. */
    NO_SUGGESTIONS("no_suggestions"),

    /** 결과는 있으나 본문이 그 뒤 수정됐다. */
    STALE("stale"),
}

data class IllustrationSuggestionResultView(
    val status: IllustrationSuggestionResultStatus,
    val contentRevision: Long,
    /** 결과가 없으면 `null`. */
    val basedOnContentRevision: Long?,
    /** 이용량 단가가 설정되지 않았으면 `null` — 0과 다르다(명세 §3). */
    val requiredCredits: BigDecimal?,
    val suggestions: List<IllustrationSuggestion>,
    val droppedCount: Int,
)

/**
 * 저장된 제안 결과를 읽어 현재 본문 버전과 대조한다(명세 §2 「stale 은 읽을 때 판정한다」).
 *
 * 결과를 트리거로 고치지 않는 이유는 R2 후보와 같다 — 본문이 바뀔 때마다 암호문을 다시 쓰면
 * 「무엇을 근거로 만든 결과인가」가 사라진다. 저장된 `based_on_content_revision` 을 그대로 두고
 * 읽을 때 비교한다.
 */
class IllustrationSuggestionResultService(
    private val enabled: Boolean,
    private val creditsPer100Chars: BigDecimal?,
    private val jobs: IllustrationSuggestionJobRepository,
    private val results: IllustrationSuggestionResultRepository,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
) {
    fun get(
        ownerId: UUID,
        conversionId: UUID,
    ): IllustrationSuggestionResultView {
        if (!enabled) throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
        return transaction.inTransaction {
            val context =
                jobs.lockOwnedContext(ownerId, conversionId)
                    ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
            val required = creditsPer100Chars?.let { requiredCreditsFor(context.charCount, it).amount }
            val stored =
                results.findLatestOwned(ownerId, conversionId)
                    ?: return@inTransaction IllustrationSuggestionResultView(
                        status = IllustrationSuggestionResultStatus.NOT_ANALYZED,
                        contentRevision = context.contentRevision,
                        basedOnContentRevision = null,
                        requiredCredits = required,
                        suggestions = emptyList(),
                        droppedCount = 0,
                    )
            val decoded =
                IllustrationSuggestionStorageCodec.decode(
                    cipher
                        .decrypt(
                            stored.payload,
                            stored.resultId,
                            EncryptedField.ILLUSTRATION_SUGGESTION_RESULT,
                        ).value,
                )
            IllustrationSuggestionResultView(
                status = statusOf(stored.basedOnContentRevision, context.contentRevision, decoded.suggestions.size),
                contentRevision = context.contentRevision,
                basedOnContentRevision = stored.basedOnContentRevision,
                requiredCredits = required,
                suggestions = decoded.suggestions,
                droppedCount = decoded.droppedCount,
            )
        }
    }

    private fun statusOf(
        basedOnContentRevision: Long,
        contentRevision: Long,
        suggestionCount: Int,
    ): IllustrationSuggestionResultStatus =
        when {
            basedOnContentRevision != contentRevision -> IllustrationSuggestionResultStatus.STALE
            suggestionCount == 0 -> IllustrationSuggestionResultStatus.NO_SUGGESTIONS
            else -> IllustrationSuggestionResultStatus.READY
        }
}
