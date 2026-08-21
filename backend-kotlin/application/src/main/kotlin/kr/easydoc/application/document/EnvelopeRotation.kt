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
}

/** **행 단위 재암호화(키 회전)** — 게이트 25 X5 / privacy-gate F-5. */
class EnvelopeRotation(
    private val documents: DocumentRepository,
    private val conversions: ConversionRepository,
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
