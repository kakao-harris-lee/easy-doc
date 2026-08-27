package kr.easydoc.application.document

import kr.easydoc.application.auth.TransactionRunner
import kr.easydoc.application.crypto.ContentCipher
import kr.easydoc.core.crypto.EncryptedContent
import kr.easydoc.core.crypto.EncryptedField
import kr.easydoc.core.crypto.PlainBody
import kr.easydoc.core.document.ConversionView
import kr.easydoc.core.document.FormatPreservation
import kr.easydoc.core.document.noOriginalPreservation
import kr.easydoc.core.document.reflectedPreservation
import kr.easydoc.core.document.unreadableOriginalPreservation
import kr.easydoc.core.easyread.ExportFormat
import kr.easydoc.core.exceptions.NotFoundException
import java.util.UUID

/** 변환 조회 유스케이스 — **내** 변환 한 건의 상태와 결과를 읽는다. */
class ConversionQueryService(
    private val conversions: ConversionRepository,
    private val cipher: ContentCipher,
    private val maskedItems: MaskedItemReader,
    private val original: OriginalReflection,
    private val transaction: TransactionRunner,
) {
    /**
     * 내 변환 한 건의 **상태와 결과**를 읽는다. 완료 전에는 결과 필드가 비어 있다.
     *
     * 원본을 **한 트랜잭션 안에서 함께** 읽는다. 판정에 원본이 필요한 갈래인지는 변환 행을
     * 읽어야 알 수 있고([needsOriginal]), 두 번 열면 그 사이에 원본이 지워질 수 있다.
     */
    fun read(
        ownerId: UUID,
        conversionId: UUID,
    ): ConversionView {
        val loaded =
            transaction.inTransaction {
                val stored = conversions.findOwnedResult(ownerId, conversionId) ?: return@inTransaction null
                stored to
                    if (needsOriginal(stored)) {
                        original.originals.read(ownerId, stored.documentId, stored.sourceFormat)
                    } else {
                        null
                    }
            } ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)
        val (stored, opened) = loaded
        return if (stored.status.exposesResult) completed(stored, opened) else beforeDone(stored)
    }

    /**
     * 서식 유지 판정에 원본 바이트가 필요한가.
     *
     * 셋을 **모두** 지나야 연다 — 최대 10MB 를 복호화하고 파싱하는 일이라 갈래를 좁게 둔다.
     * ⑴ 원본 행이 있고, ⑵ 같은 형식으로 내보낼 수단이 있고(PDF 제외), ⑶ 판정의 다른 한쪽인
     * 검수본이 이미 있다(완료).
     */
    private fun needsOriginal(stored: StoredConversion): Boolean =
        stored.hasStoredOriginal &&
            ExportFormat.ofSource(stored.sourceFormat) != null &&
            stored.status.exposesResult

    /**
     * 완료 전 응답 — **결과 필드는 하나도 실리지 않고** 배열 둘은 빈 목록이다(X-E3).
     *
     * **형식 셋은 여기서도 실린다.** 문서 메타라 변환 완료 여부와 무관하고, 계약이
     * `ConversionResponse` 설명에서 「형식 셋은 결과 필드가 아니다」로 그것을 고정한다 —
     * 실패한 변환 화면에서도 「이 문서는 DOCX 였다」는 사실은 여전히 참이다.
     */
    private fun beforeDone(stored: StoredConversion): ConversionView =
        ConversionView(
            id = stored.id,
            documentId = stored.documentId,
            status = stored.status,
            sourceFormat = stored.sourceFormat,
            exportFormat = ExportFormat.ofSource(stored.sourceFormat),
            formatPreservation = beforeDonePreservation(stored),
            easyText = null,
            editedText = null,
            reviewedAt = null,
            maskedItems = emptyList(),
            missingPlaceholders = emptyList(),
            model = null,
            providerName = null,
            inputTokens = null,
            outputTokens = null,
            failureCode = stored.failureCode,
        )

    private fun completed(
        stored: StoredConversion,
        opened: OriginalDocument?,
    ): ConversionView {
        val easyText = open(stored.id, stored.ciphertexts.easyText, EncryptedField.CONVERSION_EASY_TEXT)
        val editedText = open(stored.id, stored.ciphertexts.editedText, EncryptedField.CONVERSION_EDITED_TEXT)
        return ConversionView(
            id = stored.id,
            documentId = stored.documentId,
            status = stored.status,
            sourceFormat = stored.sourceFormat,
            exportFormat = ExportFormat.ofSource(stored.sourceFormat),
            formatPreservation = donePreservation(stored, opened, editedText ?: easyText),
            easyText = easyText,
            editedText = editedText,
            reviewedAt = stored.reviewedAt,
            maskedItems =
                open(stored.id, stored.ciphertexts.maskedItems, EncryptedField.CONVERSION_MASKED_ITEMS)
                    ?.let(maskedItems::decode)
                    ?: emptyList(),
            missingPlaceholders = stored.missingPlaceholders,
            model = stored.model,
            providerName = stored.providerName,
            inputTokens = stored.inputTokens,
            outputTokens = stored.outputTokens,
            failureCode = stored.failureCode,
        )
    }

    /**
     * 완료 **전**의 서식 유지 판정.
     *
     * 되살릴 원본이 없다는 것만 지금 확실하다 — 그 판정은 영구히 참이다. 원본이 있으면
     * `null` 이고, 그것은 「유지 불가」가 아니라 **아직 판정하지 않았다**는 뜻이다: 판정의
     * 다른 한쪽인 검수본이 아직 없고, 없는 값으로 세는 짝은 추측이다.
     *
     * **`checking` 을 내지 않는다.** 이 판정은 조회 한 번 안에서 동기로 끝나므로 클라이언트가
     * 지켜볼 진행 상태가 없다 — 여기서 「확인 중」을 내면 변환이 끝나야만 끝나는 스피너를
     * 서식 이름으로 약속하게 된다.
     */
    private fun beforeDonePreservation(stored: StoredConversion): FormatPreservation? =
        if (stored.hasStoredOriginal) null else noOriginalPreservation()

    /**
     * 완료 **후**의 서식 유지 판정 — 내보내기가 **실제로 할 반영**을 미리 재서 말한다.
     *
     * 판정과 내보내기가 같은 [OriginalStructureReflector] 를 지나고 그 안에서 같은 자리
     * 맞춤을 쓰므로, 여기서 말한 개수와 파일에 실제로 들어가는 개수가 갈릴 수 없다.
     *
     * 원본이 PDF 면 `null` 이다 — 같은 형식으로 내보낼 수단이 아직 없어(`ExportFormat.ofSource`)
     * 「반영하면 이렇게 된다」를 말할 대상 자체가 없다. 「유지 불가」로 접지 않는 이유는
     * 그것이 렌더러가 없다는 사실이지 원본 구조에 대한 판정이 아니기 때문이다.
     */
    private fun donePreservation(
        stored: StoredConversion,
        opened: OriginalDocument?,
        body: PlainBody?,
    ): FormatPreservation? =
        when {
            !stored.hasStoredOriginal -> {
                noOriginalPreservation()
            }

            // 같은 형식으로 내보낼 수단이 없다(PDF) — 반영 결과를 말할 대상 자체가 없다.
            ExportFormat.ofSource(stored.sourceFormat) == null -> {
                null
            }

            // 완료인데 초안이 없는 행은 내보내기도 열지 못한다(`ConversionExportService.requireDraft`).
            // 셀 문단을 셀 수 없으니 **판정하지 않는다** — 원본 쪽은 멀쩡하므로 「열 수 없다」가 아니다.
            body == null -> {
                null
            }

            // 원본 행이 있다고 읽었는데 같은 트랜잭션에서 열리지 않았다.
            opened == null -> {
                unreadableOriginalPreservation()
            }

            else -> {
                original.reflector
                    .outline(opened, body.value)
                    ?.let(::reflectedPreservation)
                    ?: unreadableOriginalPreservation()
            }
        }

    /** 암호문 한 열을 연다. 열이 비어 있으면 `null` — **빈 문자열로 접지 않는다.** */
    private fun open(
        record: UUID,
        column: EncryptedContent?,
        field: EncryptedField,
    ): PlainBody? = column?.let { cipher.decrypt(it, record, field) }
}
