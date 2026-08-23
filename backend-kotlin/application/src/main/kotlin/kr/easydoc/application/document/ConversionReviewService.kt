package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionStatus
import kr.easydoc.core.document.ConversionView
import kr.easydoc.core.document.MAX_CONVERTIBLE_CHARS
import kr.easydoc.core.document.charCountOf
import kr.easydoc.core.exceptions.ConflictException
import kr.easydoc.core.exceptions.InvalidInputException
import kr.easydoc.core.exceptions.NotFoundException
import kr.easydoc.core.exceptions.StorageException
import kr.easydoc.core.privacy.ReviewedBody
import kr.easydoc.core.text.stripControlChars
import java.util.UUID

/**
 * 검수 저장 — 고친 본문을 **AI 초안 옆에** 둔다(초안은 수정률 KPI 기준선이라 덮지 않는다).
 * 입력이 [ReviewedBody] 인 것이 「사람이 제출한 값만」을 타입으로 만든다.
 */
class ConversionReviewService(
    private val conversions: ConversionRepository,
    private val cipher: ContentCipher,
    private val query: ConversionQueryService,
    private val transaction: TransactionRunner,
) {
    /**
     * 저장하고 **갱신된 조회 결과**를 돌려준다(계약이 `GET` 과 같은 스키마다). 판정 순서는
     * 정규화 → 빈 값 → 길이 → 소유권 → 상태 — 앞의 셋이 **모든 식별자에 같은 응답**이라 먼저다.
     */
    fun save(
        ownerId: UUID,
        conversionId: UUID,
        submitted: ReviewedBody,
    ): ConversionView {
        val normalized = normalize(submitted)

        return transaction.inTransaction {
            val locked = lockDone(ownerId, conversionId)

            val saved =
                conversions.saveReview(
                    ownerId = ownerId,
                    expected = locked.envelope,
                    requiredStatus = ConversionStatus.DONE,
                    updated =
                        ConversionEnvelope(
                            conversionId = locked.envelope.conversionId,
                            scheme = cipher.writeScheme,
                            keyVersion = cipher.writeKeyVersion,
                            ciphertexts = sealFor(locked.envelope, normalized),
                        ),
                )
            // 잠금 아래에서 조건은 참이다. 거짓이면 상태 충돌이 아니라 **잠금이 서지 않았다는
            // 신호**라 409 로 접지 않는다.
            if (!saved) throw StorageException(REVIEW_NOT_SAVED_MESSAGE)

            query.read(ownerId, conversionId)
        }
    }

    /** 자원 판정 — 내 것인가(404), 고칠 수 있는가(409). 통과한 행을 **잠근 채로** 준다. */
    private fun lockDone(
        ownerId: UUID,
        conversionId: UUID,
    ): LockedConversion {
        val locked =
            conversions.lockOwnedForReview(ownerId, conversionId)
                ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
        if (locked.status != ConversionStatus.DONE) throw ConflictException(CONVERSION_NOT_DONE_MESSAGE)
        return locked
    }

    /**
     * 제어문자를 걷어낸 뒤 **그 결과로** 판정한다. 빈 값만 앞뒤 공백을 털고 길이·저장 값은 털지
     * 않는다(계약이 정규화를 제어문자 제거로만 정의했다). 길이는 **코드 포인트**.
     */
    private fun normalize(submitted: ReviewedBody): PlainBody {
        val stripped = stripControlChars(submitted.value)
        if (stripped.isBlank()) throw InvalidInputException(EMPTY_REVIEW_MESSAGE)
        if (charCountOf(stripped) > MAX_CONVERTIBLE_CHARS) throw InvalidInputException(REVIEW_TOO_LONG_MESSAGE)
        // 저장 정의역은 `PlainBody` 가 끊는다 — 길이와 다른 축이다.
        return PlainBody(stripped)
    }

    /**
     * 세 열의 **최종 값**. 라벨이 열 내용과 어긋난 행은 영영 열리지 않으므로 셋을 **같은 세대**로
     * 맞춘다. 이미 쓰기 세대면 나머지 둘은 **읽은 바이트 그대로**, 아니면 열어 다시 봉인한다.
     * **옛 키로는 어느 갈래에서도 쓰지 않는다.**
     */
    private fun sealFor(
        envelope: ConversionEnvelope,
        edited: PlainBody,
    ): ConversionCiphertexts {
        val editedSealed = cipher.encrypt(edited, envelope.conversionId, EncryptedField.CONVERSION_EDITED_TEXT)
        if (envelope.scheme == cipher.writeScheme && envelope.keyVersion == cipher.writeKeyVersion) {
            return ConversionCiphertexts(
                easyText = envelope.ciphertexts.easyText,
                maskedItems = envelope.ciphertexts.maskedItems,
                editedText = editedSealed,
            )
        }
        val record = envelope.conversionId
        return ConversionCiphertexts(
            easyText = reseal(record, envelope.ciphertexts.easyText, EncryptedField.CONVERSION_EASY_TEXT),
            maskedItems = reseal(record, envelope.ciphertexts.maskedItems, EncryptedField.CONVERSION_MASKED_ITEMS),
            editedText = editedSealed,
        )
    }

    /** 열어서 쓰기 세대로 다시 봉인한다 — **복호화한 평문 그대로**(재직렬화하지 않는다). */
    private fun reseal(
        record: UUID,
        column: EncryptedContent?,
        field: EncryptedField,
    ): EncryptedContent? = column?.let { cipher.encrypt(cipher.decrypt(it, record, field), record, field) }
}
