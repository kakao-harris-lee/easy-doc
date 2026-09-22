package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.application.dictionary.ReviewedDefinitionSource
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.dictionary.Explanation
import kr.easydoc.core.dictionary.deriveExplanations
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.segment.splitUnits
import java.util.UUID

/**
 * 조회 응답 하나 (R6, 계약 `Explanations`).
 *
 * [toString] 은 개수만 남긴다 — [Explanation] 자신은 마스킹돼 있지만, 이 뷰가 다시 목록으로
 * 로그에 찍히는 자리에서까지 매번 그 목록을 펼치게 둘 이유가 없다.
 */
data class ExplanationsView(
    val conversionId: UUID,
    val currentContentRevision: Long,
    val explanations: List<Explanation>,
) {
    override fun toString(): String =
        "ExplanationsView($conversionId, revision=$currentContentRevision, explanations=${explanations.size})"
}

/**
 * R6 「근거 있는 추가 설명」 조회. LLM을 호출하지 않는다.
 *
 * 설명은 요청 시점에 새로 만들지 않고 [ReviewedDefinitionSource] 가 돌려주는, **이미 검수를
 * 마친** 정의에서 파생한다 - 그래서 근거가 있고 추가 지연·비용이 붙지 않는다. 근거 위치를
 * 얻으려면 원문이 필요한데, 이 조회는 [ReviewSupportService.analyze] 처럼 그 원문을 저장하지
 * 않고 매번 (원문, 본문)에서 다시 계산한다 - 저장하면 본문이 바뀔 때마다 다시 만들어야 하는
 * 행이 하나 늘 뿐이다.
 *
 * [SegmentMapDerivation] 을 쓰지 않는다 - [deriveExplanations] 는 [kr.easydoc.core.segment.SegmentMap]
 * 이 아니라 평평한 원문 단위 목록만 받으므로 정렬까지는 필요 없다. 원문을 못 읽으면(예: 보존
 * 기간이 지남) [splitUnits] 에 넘길 원문 자체가 없을 뿐이라 빈 목록으로 대신한다 - 그래도
 * [ReviewedDefinitionSource] 는 그대로 부른다: 검수된 정의는 원문과 무관하게 존재하므로,
 * 조회를 막거나 설명 자체를 비우지 않고 근거([Explanation.sourceAnchors])만 빈다.
 */
class ExplanationsService(
    private val enabled: Boolean,
    private val conversions: ConversionRepository,
    private val definitions: ReviewedDefinitionSource,
    private val documents: DocumentRepository,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
) {
    @Suppress("ThrowsCount") // 미존재·상태·본문누락을 계약의 서로 다른 실패(404/409)로 유지한다.
    fun read(
        ownerId: UUID,
        conversionId: UUID,
    ): ExplanationsView {
        requireEnabled()
        val (source, body, revision) =
            transaction.inTransaction {
                val conversion =
                    conversions.findOwnedResult(ownerId, conversionId)
                        ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
                if (conversion.status != ConversionStatus.DONE) throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)
                val body = currentBody(conversion) ?: throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)
                val source = documents.findOwnedSource(ownerId, conversion.documentId)
                Triple(source, body, conversion.contentRevision)
            }

        // 복호화·분할은 트랜잭션 밖에서 한다 - ReviewSupportService.analyze와 같은 경계다.
        // 원문을 못 읽어도(예: 보존 기간이 지남) 조회 자체를 막지 않는다 - 근거만 빈다.
        val sourceUnits =
            source
                ?.let {
                    splitUnits(cipher.decrypt(it.sourceText, it.documentId, EncryptedField.DOCUMENT_SOURCE_TEXT).value)
                }
                ?: emptyList()
        val explanations = deriveExplanations(definitions.find(body.value), sourceUnits)
        return ExplanationsView(conversionId, revision, explanations)
    }

    private fun currentBody(conversion: StoredConversion) =
        conversion.ciphertexts.editedText
            ?.let { cipher.decrypt(it, conversion.id, EncryptedField.CONVERSION_EDITED_TEXT) }
            ?: conversion.ciphertexts.easyText
                ?.let { cipher.decrypt(it, conversion.id, EncryptedField.CONVERSION_EASY_TEXT) }

    private fun requireEnabled() {
        if (!enabled) throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
    }
}
