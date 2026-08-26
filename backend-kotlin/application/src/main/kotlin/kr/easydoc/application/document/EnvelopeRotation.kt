package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import java.util.UUID

/** 행 하나를 회전한 결과. */
enum class RotationOutcome {
    /** 새 세대로 다시 봉인했다. */
    ROTATED,

    /** 이미 쓰기 세대·방식이다. 할 일이 없다. */
    ALREADY_CURRENT,

    /** 그 식별자의 행이 없다. */
    MISSING,

    /** **잠근 채 읽은 그 행이 쓰기 시점에 그대로가 아니었다** — 0행이 갱신됐다. */
    CONTENDED,

    /**
     * **행은 있는데 봉인된 것이 하나도 없다.** 회전할 암호문이 없으므로 아무것도 쓰지 않았다.
     *
     * [ALREADY_CURRENT] 와 나누는 이유: 그쪽은 「이 행이 새 세대다」라고 말하지만 이 행은
     * **어느 세대에도 매여 있지 않다**(봉투 열까지 NULL 이다). 옛 세대를 내려도 되는지를
     * 묻는 운영자에게는 둘 다 「안전」이지만, 두 사실을 같은 값으로 보고하면 회전 배치의
     * 집계가 「전부 새 세대로 옮겼다」고 말하면서 실제로는 한 번도 쓰지 않은 행을 센다.
     *
     * 지금은 `conversion_feedback` 만 이 값을 낸다 — 봉투 열이 NULL 을 허용하는 유일한 표다.
     */
    NOTHING_SEALED,
}

/** **행 단위 재암호화(키 회전)** — 게이트 25 X5 / privacy-gate F-5. */
class EnvelopeRotation(
    private val documents: DocumentRepository,
    private val conversions: ConversionRepository,
    private val feedback: ConversionFeedbackRepository,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
) {
    /** `documents` 한 행을 회전한다. */
    fun rotateDocument(documentId: UUID): RotationOutcome =
        transaction.inTransaction {
            val current = documents.lockSourceText(documentId) ?: return@inTransaction RotationOutcome.MISSING
            if (isCurrent(current.scheme, current.keyVersion)) return@inTransaction RotationOutcome.ALREADY_CURRENT

            val resealed = reseal(current, documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)
            val updated = documents.rewriteEnvelope(documentId, current, resealed)
            if (updated) RotationOutcome.ROTATED else RotationOutcome.CONTENDED
        }

    /** `conversions` 한 행을 회전한다. 암호문 세 열을 함께 다시 봉인한다. */
    fun rotateConversion(conversionId: UUID): RotationOutcome =
        transaction.inTransaction {
            val envelope = conversions.lockEnvelope(conversionId) ?: return@inTransaction RotationOutcome.MISSING
            if (isCurrent(envelope.scheme, envelope.keyVersion)) return@inTransaction RotationOutcome.ALREADY_CURRENT

            val columns = envelope.ciphertexts
            // 「실패 시 전체 중단」 — 세 열을 **먼저 전부** 연다. 하나라도 열리지 않으면
            // 여기서 예외가 나가고 아래 UPDATE 는 아예 불리지 않는다.
            val easyText = columns.easyText?.let { open(it, conversionId, EncryptedField.CONVERSION_EASY_TEXT) }
            val maskedItems =
                columns.maskedItems?.let { open(it, conversionId, EncryptedField.CONVERSION_MASKED_ITEMS) }
            val editedText = columns.editedText?.let { open(it, conversionId, EncryptedField.CONVERSION_EDITED_TEXT) }

            val resealed =
                ConversionCiphertexts(
                    easyText = easyText?.let { cipher.encrypt(it, conversionId, EncryptedField.CONVERSION_EASY_TEXT) },
                    maskedItems =
                        maskedItems?.let {
                            cipher.encrypt(it, conversionId, EncryptedField.CONVERSION_MASKED_ITEMS)
                        },
                    editedText =
                        editedText?.let {
                            cipher.encrypt(it, conversionId, EncryptedField.CONVERSION_EDITED_TEXT)
                        },
                )

            val updated =
                conversions.rewriteEnvelope(
                    // 잠근 채 읽은 그 행이 그대로 쓰기 조건이다. 정수 하나를 넘기면 조건을
                    // 좁게 쓰는 갈래가 생기고, 그 자유가 게이트 27 ① 의 결함이었다.
                    expected = envelope,
                    scheme = cipher.writeScheme,
                    keyVersion = cipher.writeKeyVersion,
                    ciphertexts = resealed,
                )
            if (updated) RotationOutcome.ROTATED else RotationOutcome.CONTENDED
        }

    /**
     * `conversion_feedback` 한 행을 회전한다. 봉인된 열은 자유 의견 하나다.
     *
     * 자유 의견이 없는 행(봉투 세 열이 전부 NULL)은 [RotationOutcome.NOTHING_SEALED] 다 —
     * `conversions` 의 「빈 행도 봉투는 올린다」와 갈리는 자리인데, 그쪽은 봉투 두 열이
     * `NOT NULL` 이라 행 단위 세대가 실재하는 반면 여기는 올릴 봉투 자체가 없기 때문이다.
     * 빈 문자열을 암호화해 봉투를 만들어 넣는 것은 없던 내용을 지어내는 일이고, 선택 항목인
     * 의견을 「빈 의견을 남겼다」로 바꿔 버린다.
     */
    fun rotateFeedback(conversionId: UUID): RotationOutcome =
        transaction.inTransaction {
            val locked = feedback.lockComment(conversionId) ?: return@inTransaction RotationOutcome.MISSING
            val sealed = locked.comment ?: return@inTransaction RotationOutcome.NOTHING_SEALED
            if (isCurrent(sealed.scheme, sealed.keyVersion)) return@inTransaction RotationOutcome.ALREADY_CURRENT

            val resealed = reseal(sealed, conversionId, EncryptedField.CONVERSION_FEEDBACK_COMMENT)
            // 잠근 채 읽은 그 암호문이 쓰기 조건이다 — `rotateConversion` 과 같은 판단이다.
            val updated = feedback.rewriteComment(conversionId, expected = sealed, comment = resealed)
            if (updated) RotationOutcome.ROTATED else RotationOutcome.CONTENDED
        }

    /** 이 행이 이미 현재 쓰기 봉투인가. 방식과 세대를 **둘 다** 본다 — 방식만 바뀌는 회전도 있다. */
    private fun isCurrent(
        scheme: String,
        keyVersion: Int,
    ): Boolean = scheme == cipher.writeScheme && keyVersion == cipher.writeKeyVersion

    private fun open(
        content: EncryptedContent,
        record: UUID,
        field: EncryptedField,
    ) = cipher.decrypt(content, record, field)

    private fun reseal(
        content: EncryptedContent,
        record: UUID,
        field: EncryptedField,
    ): EncryptedContent = cipher.encrypt(open(content, record, field), record, field)
}
