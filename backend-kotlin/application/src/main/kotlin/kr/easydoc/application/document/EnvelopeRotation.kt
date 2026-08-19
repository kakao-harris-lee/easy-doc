package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import java.util.UUID

/**
 * 행 하나를 회전한 결과.
 *
 * 「갱신됐다/안 됐다」 불리언 하나로 돌려주면 **세 가지 다른 사정**이 한 값으로 뭉개진다 —
 * 없는 행 · 이미 최신 세대 · 경합에서 진 행. 회전 배치는 셋에 각각 다르게 반응해야 한다
 * (건너뛴다 / 세지 않는다 / 다시 시도한다).
 */
enum class RotationOutcome {
    /** 새 세대로 다시 봉인했다. */
    ROTATED,

    /** 이미 쓰기 세대·방식이다. 할 일이 없다. */
    ALREADY_CURRENT,

    /** 그 식별자의 행이 없다. */
    MISSING,

    /**
     * 낙관적 조건(`WHERE key_version = :expected`)이 맞지 않아 0행이 갱신됐다.
     *
     * 다른 프로세스가 같은 행을 먼저 회전했다는 뜻이다. 잠금 없이 경합을 안전하게 만드는
     * 대가이며, 호출자는 다시 읽어 재시도하거나 다음 행으로 넘어간다.
     */
    CONTENDED,
}

/**
 * **행 단위 재암호화(키 회전)** — 게이트 25 X5 / privacy-gate F-5.
 *
 * 옛 세대로 봉인된 행을 읽어 현재 쓰기 세대로 다시 봉인한다. 회전이 유스케이스인 이유는
 * 「복호 → 재암호 → 단일 UPDATE」가 트랜잭션과 실패 정책을 함께 지기 때문이다 — 저장소에
 * 두면 저장소가 그 둘까지 지게 된다(계획 §4.1-3).
 *
 * ## 원장이 연 네 조건과, 그것을 **구조로** 강제하는 방법
 *
 * - **단일 UPDATE** — 저장소 포트가 암호문 세 열을 [ConversionCiphertexts] 로 **함께** 받는다.
 *   열 하나짜리 갱신 메서드가 없다: 두 문장으로 나누면 "세대는 v2 인데 한 열은 v1 암호문" 인
 *   행이 생기고, 그 행은 영원히 열리지 않는다.
 * - **NULL 보존** — 널을 그대로 넘긴다. 빈 문자열을 암호화하지 않는다: 없던 내용을 지어내는
 *   것이고 되돌릴 수 없다.
 * - **실패 시 전체 중단** — 세 열을 **전부 복호화한 뒤에** 암호화한다. 하나라도 열리지 않으면
 *   `DecryptionFailedException` 이 UPDATE 전에 터지고 트랜잭션이 롤백된다.
 * - **평문 체류 최소화** — 평문을 컬렉션·필드·로그 어디에도 두지 않는다. 지역 변수로만 들고
 *   한 함수 안에서 끝낸다.
 *
 * 덤으로 **동시 회전**이 안전하다 — 낙관적 조건이 두 프로세스 중 뒤엣것을 0행으로 만든다.
 *
 * ## 이 클래스가 **누구에게 불리는가는 아직 정해지지 않았다**
 *
 * 운영 CLI · worker 스케줄 · 마이그레이션 중 무엇이 이 유스케이스의 호출자인지는 열린
 * 질문이다(계획 §9 질문 ⑦). 그 판정 전까지 이 단위가 만드는 것은 **포트·구현·테스트**까지다.
 *
 * ## 로그
 *
 * 아무것도 로깅하지 않는다. 회전 배치가 남겨야 하는 것은 `conversion_id`·건수·소요이고
 * (계획 §4.3), 그것은 호출자가 셀 값이지 이 클래스가 낼 값이 아니다. 여기서 로그를 내면
 * 그 줄이 복호화 실패의 원인을 담게 되는데, 그 원인은 I-7 이 구분하지 말라고 한 것이다.
 */
class EnvelopeRotation(
    private val documents: DocumentRepository,
    private val conversions: ConversionRepository,
    private val cipher: ContentCipher,
    private val transaction: TransactionRunner,
) {
    /**
     * `documents` 한 행을 회전한다.
     *
     * 컬럼이 하나(`source_text_encrypted`)이고 `NOT NULL` 이라 「NULL 보존」 조건이 없다.
     * 나머지 셋은 [rotateConversion] 과 같은 방식으로 선다.
     */
    fun rotateDocument(documentId: UUID): RotationOutcome =
        transaction.inTransaction {
            val current = documents.loadSourceText(documentId) ?: return@inTransaction RotationOutcome.MISSING
            if (isCurrent(current.scheme, current.keyVersion)) return@inTransaction RotationOutcome.ALREADY_CURRENT

            val resealed = reseal(current, documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)
            val updated = documents.rewriteEnvelope(documentId, current.keyVersion, resealed)
            if (updated) RotationOutcome.ROTATED else RotationOutcome.CONTENDED
        }

    /**
     * `conversions` 한 행을 회전한다. 암호문 세 열을 함께 다시 봉인한다.
     *
     * 세 열이 전부 NULL 인 대기 중 변환도 회전 대상이다 — 그 행의 봉투 두 값은 **앞으로 쓸
     * 세대**를 가리키므로, 회전 뒤에 워커가 결과를 쓸 때 행의 세대와 새 암호문의 세대가
     * 어긋나지 않아야 한다.
     */
    fun rotateConversion(conversionId: UUID): RotationOutcome =
        transaction.inTransaction {
            val envelope = conversions.loadEnvelope(conversionId) ?: return@inTransaction RotationOutcome.MISSING
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
                    conversionId = conversionId,
                    expectedKeyVersion = envelope.keyVersion,
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
